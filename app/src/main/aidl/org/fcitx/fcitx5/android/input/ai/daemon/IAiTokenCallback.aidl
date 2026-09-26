package org.fcitx.fcitx5.android.input.ai.daemon;

interface IAiTokenCallback {
    void onNextToken(String token);
    void onComplete(String fullResponse, long ttftMs, long totalMs);
    void onError(int errorCode, String errorMessage);
}
