package com.ignitera.backend.controller;

import com.ignitera.backend.service.EmailService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMethod;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
public class InquiryController {

    private static final Logger log = LoggerFactory.getLogger(InquiryController.class);

    private final EmailService emailService;

    public InquiryController(EmailService emailService) {
        this.emailService = emailService;
    }

    @PostMapping("/inquiry")
    public ResponseEntity<Void> inquiry(@Valid @RequestBody InquiryRequest request) {
        log.info("[InquiryController] POST /api/inquiry — company=({}), industry=({}), email=({}), phone=({}), planName=({})",
                request.companyName(),
                request.industry(),
                maskEmailForLog(request.email()),
                maskPhoneForLog(request.phone()),
                request.planName());

        try {
            emailService.sendDirectInquiryEmail(
                    request.companyName(),
                    request.industry(),
                    request.email(),
                    request.phone(),
                    request.planName()
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[InquiryController] 直接問い合わせのメール通知に失敗しました", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private String maskEmailForLog(String email) {
        if (email == null) return "(null)";
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private String maskPhoneForLog(String phone) {
        if (phone == null) return "(null)";
        String v = phone.strip();
        if (v.length() <= 4) return "***";
        return "***" + v.substring(v.length() - 4);
    }

    public record InquiryRequest(
            String companyName,
            String industry,
            @NotBlank @Email String email,
            String phone,
            String planName
    ) {}
}
