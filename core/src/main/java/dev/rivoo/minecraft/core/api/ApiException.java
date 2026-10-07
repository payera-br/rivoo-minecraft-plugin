package dev.rivoo.minecraft.core.api;

import java.io.IOException;

public final class ApiException extends IOException {
    public final int httpStatus;
    public final long retryAfterSeconds;

    public ApiException(int httpStatus, String body, long retryAfterSeconds) {
        super("HTTP " + httpStatus + ": " + body);
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public boolean rateLimited() {
        return httpStatus == 429;
    }
}
