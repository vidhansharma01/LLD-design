package lld.chainOfResponsibility;

public class Level2SupportHandler implements SupportHandler{
    private SupportHandler nextSupportHandler;
    @Override
    public void handleRequest(Request request) {
        if(request.getPriority() == Priority.INTERMEDIATE)
            System.out.println("Level 2 Support handled the request.");
        else if (nextSupportHandler != null)
            nextSupportHandler.handleRequest(request);
    }

    @Override
    public void setNextHandler(SupportHandler supportHandler) {
        this.nextSupportHandler = supportHandler;
    }
}
