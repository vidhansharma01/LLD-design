package lld.chainOfResponsibility;

public class Level1SupportHandler implements SupportHandler{
    private SupportHandler nextSupportHandler;
    @Override
    public void handleRequest(Request request) {
        if (request.getPriority() == Priority.BASIC)
            System.out.println("Level 1 Support handled the request.");
        else if(nextSupportHandler != null)
            nextSupportHandler.handleRequest(request);
    }

    @Override
    public void setNextHandler(SupportHandler supportHandler) {
        nextSupportHandler = supportHandler;
    }
}
