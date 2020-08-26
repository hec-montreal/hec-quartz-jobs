package ca.hec.jobs.api.calendar;

public class HecCalendarJobExecutionException extends Exception {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    private boolean shouldContinue = false;

    public HecCalendarJobExecutionException() {
        super();
    }

    public HecCalendarJobExecutionException(boolean shouldContinue) {
        super();
        setContinue(shouldContinue);
    }

    public void setContinue(boolean shouldContinue) {
        this.shouldContinue = shouldContinue;
    }

    public boolean getContinue() {
        return this.shouldContinue;
    }
}