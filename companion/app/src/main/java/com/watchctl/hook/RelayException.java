package com.watchctl.hook;

/** 中继层统一异常：带业务 code，便于回传给手表端。 */
public class RelayException extends RuntimeException {
    public final String code;
    public RelayException(String code, String message) {
        super(message);
        this.code = code;
    }
}
