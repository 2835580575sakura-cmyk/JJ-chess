package dev.sakura.xiangqi.engine;
import dev.sakura.xiangqi.engine.IEngineCallback;
interface IEngineService {
    void initialize(String optionsJson, IEngineCallback callback);
    void validate(long requestId, String historyJson);
    void analyze(String requestJson);
    void cancel(long searchId);
    void shutdown();
}
