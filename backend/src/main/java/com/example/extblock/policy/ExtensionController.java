package com.example.extblock.policy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.extblock.common.ClientInfo;

@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {

	private final ExtensionService service;

	public ExtensionController(ExtensionService service) {
		this.service = service;
	}

	@GetMapping
	public ExtensionView getAll() {
		return service.getAll();
	}

	@PutMapping("/fixed/{id}")
	public FixedView setFixedBlocked(@PathVariable long id, @Valid @RequestBody SetBlockedRequest body,
			HttpServletRequest request) {
		return service.setFixedBlocked(id, body.blocked(), ClientInfo.from(request));
	}

	@PostMapping("/custom")
	@ResponseStatus(HttpStatus.CREATED)
	public CustomView addCustom(@RequestBody AddCustomRequest body, HttpServletRequest request) {
		return service.addCustom(body.name(), ClientInfo.from(request));
	}

	@DeleteMapping("/custom/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCustom(@PathVariable long id, HttpServletRequest request) {
		service.deleteCustom(id, ClientInfo.from(request));
	}

}
