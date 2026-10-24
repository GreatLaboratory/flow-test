package com.example.extblock.policy;

import jakarta.validation.constraints.NotNull;

/** toggle이 아닌 목표 상태 — 재시도·더블클릭에도 멱등. */
public record SetBlockedRequest(@NotNull Boolean blocked) {
}
