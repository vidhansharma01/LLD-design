package lld.chainOfResponsibility;

public class Request {
    private final Priority priority;

    public Request(Priority priority) {
        this.priority = priority;
    }
    public Priority getPriority(){
        return priority;
    }
}
