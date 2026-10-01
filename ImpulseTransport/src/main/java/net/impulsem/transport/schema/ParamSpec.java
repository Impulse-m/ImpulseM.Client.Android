package net.impulsem.transport.schema;


public final class ParamSpec {

    public enum Kind {
        INT,
        LONG,
        DOUBLE,
        STRING,
        BYTES,
        INT128,
        INT256,
        BOOL,
        TRUE,
        FLAGS,
        VECTOR,
        OBJECT
    }


    public String name = "";
    public Kind kind;
    public ParamSpec elem;
    public String typeName;
    public String flagRegister;
    public int flagBit;
    public int field;
    public String derivedFrom;
}
