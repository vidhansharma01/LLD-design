package lld.chainOfResponsibility;

public class Main {
    public static void main(String[] args){
        SupportHandler supportHandlerL1 = new Level1SupportHandler();
        SupportHandler supportHandlerL2 = new Level2SupportHandler();

        supportHandlerL1.setNextHandler(supportHandlerL2);
        Request request = new Request(Priority.BASIC);

        supportHandlerL1.handleRequest(request);
    }
}
