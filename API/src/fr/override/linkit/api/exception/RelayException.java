/*
 * Copyright (c) 2021. Linkit and or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can only use it for personal uses, studies or documentation.
 * You can download this source code, and modify it ONLY FOR PERSONAL USE and you
 * ARE NOT ALLOWED to distribute your MODIFIED VERSION.
 *
 * Please contact maximebatista18@gmail.com if you need additional information or have any
 * questions.
 */

package fr.override.linkit.api.exception;

/**
 * thrown to report an internal incident in the Relays
 * */
public class RelayException extends Exception {

    public RelayException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public RelayException(String msg) {
        super(msg);
    }

}
