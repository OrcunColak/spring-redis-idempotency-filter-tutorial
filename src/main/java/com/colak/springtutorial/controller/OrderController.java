package com.colak.springtutorial.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "api/v1/orders")

@RequiredArgsConstructor
public class OrderController {

    // curl -H "rid:1" -H "sid:2" http://localhost:8080/api/v1/orders
    // http://localhost:8080/api/v1/orders
    @GetMapping
    public String createOrder() {
        return "Order created";
    }
}
