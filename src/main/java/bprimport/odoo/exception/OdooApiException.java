package bprimport.odoo.exception;

public class OdooApiException extends RuntimeException {

    private final int odooCode;

    public OdooApiException(String message) {
        super(message);
        this.odooCode = -1;
    }

    public OdooApiException(String message, int odooCode) {
        super(message);
        this.odooCode = odooCode;
    }

    public OdooApiException(String message, Throwable cause) {
        super(message, cause);
        this.odooCode = -1;
    }

    public int getOdooCode() { return odooCode; }
}
