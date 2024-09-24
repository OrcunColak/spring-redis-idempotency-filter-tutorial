package com.colak.springtutorial.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

public class ResponseWrapperUtil {

    public static String getResponseBody(ContentCachingResponseWrapper responseCopier, HttpServletRequest request)
            throws UnsupportedEncodingException {
        return new String(responseCopier.getContentAsByteArray(), request.getCharacterEncoding());
    }

    public static Map<String, String> getHeadersMap(ContentCachingResponseWrapper responseWrapper) {
        Map<String, String> headersMap = new HashMap<>();

        // Iterate over all header names
        for (String headerName : responseWrapper.getHeaderNames()) {
            // Get the value for each header
            String headerValue = responseWrapper.getHeader(headerName);
            headersMap.put(headerName, headerValue);
        }
        return headersMap;
    }
}
