package org.fcitx.fcitx5.android.input.ai.daemon;

import org.fcitx.fcitx5.android.input.ai.daemon.IAiTokenCallback;

interface IAiDaemonService {
    int getDaemonPid();
    boolean isWarm();
    void warmupPromptCache(String systemPrompt, String personaContext);
    void generateStreaming(String prompt, in IAiTokenCallback callback);
    String generatePromptLookupSync(String prompt, String referenceContext, int maxTokens);
    void evictCache();
}
