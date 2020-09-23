package fr.overridescala.vps.ftp.api.exceptions;

/**
 * thrown to report an internal incident in the Tasks
 * */
public class TaskException extends RelayException {

    public TaskException(String msg) {
        super(msg);
    }

}
