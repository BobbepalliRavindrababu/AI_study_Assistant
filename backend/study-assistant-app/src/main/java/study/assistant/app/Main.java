package study.assistant.app;

import study.assistant.document.DocumentService;
import study.assistant.ai.AiService;
import study.assistant.api.ApiServer;

public class Main {
    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("       AI Study Assistant Backend Services       ");
        System.out.println("=================================================");
        
        int port = 8085;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port specified, using default 8080.");
            }
        }

        try {
            DocumentService documentService = new DocumentService();
            AiService aiService = new AiService();
            ApiServer apiServer = new ApiServer(port, documentService, aiService);
            
            apiServer.start();
            
            final ApiServer finalServer = apiServer;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutdown hook triggered. Stopping server...");
                finalServer.stop();
            }));

            System.out.println("Backend is ready for requests.");
        } catch (Exception e) {
            System.err.println("Fatal error starting application: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
