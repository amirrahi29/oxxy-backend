package com.oxxy.Oxxy.App.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class ProductController {

	@GetMapping
	public List<Product> getProducts() {
		return List.of(
				new Product(1L, "Laptop11", 9999.99),
				new Product(2L, "Phone11", 4990.99),
				new Product(3L, "Headphones11", 799.99));
	}

	public record Product(Long id, String name, double price) {
	}

}