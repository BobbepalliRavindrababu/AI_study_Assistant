module study.assistant.api {
    requires jdk.httpserver;
    requires com.google.gson;
    requires study.assistant.document;
    requires study.assistant.ai;
    exports study.assistant.api;
}
