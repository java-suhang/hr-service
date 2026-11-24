package com.hr.controller;

import java.io.IOException;
import java.util.Locale;

import com.hr.service.AttachmentService;
import com.hr.util.ApiException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

/** 파일 업로드 요청(multipart/form-data) 공통 처리 (C) */
public final class Multipart {

    /** @MultipartConfig에 쓰는 한도: 파일 5MB, 요청 전체 6MB */
    public static final long MAX_FILE = AttachmentService.MAX_BYTES;
    public static final long MAX_REQUEST = AttachmentService.MAX_BYTES + 1024 * 1024;

    private Multipart() {
    }

    /** 파일 파트를 꺼낸다. multipart가 아니면 400, 한도를 넘으면 413 */
    public static Part file(HttpServletRequest req, String name) throws IOException {
        String type = req.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw ApiException.badRequest("파일은 multipart/form-data로 보내야 합니다");
        }
        try {
            return req.getPart(name);
        } catch (IllegalStateException e) {
            // Tomcat은 파일·요청 크기 한도를 넘으면 IllegalStateException을 던진다
            throw AttachmentService.tooLarge();
        } catch (ServletException e) {
            throw ApiException.badRequest("업로드 요청을 읽을 수 없습니다");
        }
    }
}
