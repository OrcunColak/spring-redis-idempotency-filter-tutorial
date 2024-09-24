package com.colak.springtutorial.filter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.lang.String.join;
import static org.springframework.http.HttpStatus.TOO_EARLY;

@Slf4j
@RequiredArgsConstructor
public class IdempotenceFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_KEY = "rid";

    private static final String SERVICE_ID_KEY = "sid";

    public static final String DELIMITER = "_";

    private final RedisTemplate<String, IdempotencyValue> redisTemplate;

    private final long ttl;

    private final ObjectMapper OBJECT_MAPPER = initObjectMapper();

    private ObjectMapper initObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain)
            throws ServletException, IOException {
        log.debug("start IdempotenceFilter");

        String method = httpServletRequest.getMethod();
        String requestId = httpServletRequest.getHeader(REQUEST_ID_KEY);
        String serviceId = httpServletRequest.getHeader(SERVICE_ID_KEY);

        // GET_/api/v1/orders_2_1
        String cacheKey = join(DELIMITER,
                method, httpServletRequest.getRequestURI(), serviceId, requestId);

        if (isNotTargetMethod(method)) {
            log.info("Request method {} didn't match the target idempotency https method.", method);
            filterChain.doFilter(httpServletRequest, httpServletResponse);
        } else if (StringUtils.isBlank(requestId)
                   || StringUtils.isBlank(serviceId)) {
            log.warn("Request should bring a RequestId and ClientId in header, but no. get rid = {}, sid = {}.", requestId, serviceId);
            filterChain.doFilter(httpServletRequest, httpServletResponse);
        } else {
            log.info("requestId and serviceId not empty, rid = {}, sid = {}", requestId, serviceId);
            BoundValueOperations<String, IdempotencyValue> keyOperation = redisTemplate.boundValueOps(cacheKey);
            boolean isAbsent = Boolean.TRUE.equals(keyOperation.setIfAbsent(IdempotencyValue.init(), ttl, TimeUnit.MINUTES));
            if (isAbsent) {
                log.info("cache {} does not exist ", cacheKey);
                ContentCachingResponseWrapper responseCopier = new ContentCachingResponseWrapper(httpServletResponse);

                filterChain.doFilter(httpServletRequest, responseCopier);

                updateResultInCache(httpServletRequest, responseCopier, keyOperation);
                responseCopier.copyBodyToResponse();
            } else {
                log.info("cache {} exists ", cacheKey);
                handleWhenCacheExist(httpServletRequest, httpServletResponse, keyOperation);
            }
        }
    }

    private boolean isNotTargetMethod(String method) {
        return !HttpMethod.GET.matches(method);
    }

    private void updateResultInCache(HttpServletRequest httpServletRequest, ContentCachingResponseWrapper responseCopier,
                                     BoundValueOperations<String, IdempotencyValue> keyOperation)
            throws UnsupportedEncodingException {
        if (needCache(responseCopier)) {
            log.info("result needs to be cached");
            String responseBody = ResponseWrapperUtil.getResponseBody(responseCopier, httpServletRequest);
            Map<String, String> headersMap = ResponseWrapperUtil.getHeadersMap(responseCopier);

            // Create result to be cached
            IdempotencyValue result = IdempotencyValue.done(headersMap, responseCopier.getStatus(), responseBody);

            log.info("save {} to redis", result);
            keyOperation.set(result, ttl, TimeUnit.MINUTES);
        } else {
            log.info("process result don't need to be cached");
            redisTemplate.delete(keyOperation.getKey());
        }
    }

    private void handleWhenCacheExist(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                                      BoundValueOperations<String, IdempotencyValue> keyOperation)
            throws IOException {
        IdempotencyValue cachedResponse = keyOperation.get();
        assert cachedResponse != null;

        log.info("cached content = {} ", cachedResponse);
        String responseBody;
        int status;


        if (cachedResponse.isDone) {
            log.info("cache exists, and is done.");
            status = cachedResponse.status;
            responseBody = cachedResponse.cacheValue;
        } else {
            log.info("cache exist, and is still in processing, please retry later");
            status = TOO_EARLY.value();
            ProblemDetail pd = ProblemDetail.forStatus(TOO_EARLY);
            pd.setType(URI.create(httpServletRequest.getRequestURI()));
            pd.setDetail("request is now processing, please try again later");
            responseBody = OBJECT_MAPPER.writeValueAsString(pd);
        }
        httpServletResponse.setStatus(status);
        httpServletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);

        PrintWriter responseWriter = httpServletResponse.getWriter();
        responseWriter.write(responseBody);

        httpServletResponse.flushBuffer();

    }

    private boolean needCache(ContentCachingResponseWrapper responseCopier) {
        int statusCode = responseCopier.getStatus();
        return statusCode >= 200
               && statusCode < 300;
    }

    public record IdempotencyValue(Map<String, String> header, int status, String cacheValue, boolean isDone) {

        private static IdempotencyValue init() {
            return new IdempotencyValue(Collections.emptyMap(), 0, "", false);
        }

        private static IdempotencyValue done(Map<String, String> header, Integer status, String cacheValue) {
            return new IdempotencyValue(header, status, cacheValue, true);
        }
    }
}
