package net.impulsem.transport.schema;


public final class ResultSpec {

    /** One of: object, list, bool, int, long. */
    public String kind;
    public String typeName;
    public ParamSpec elem;
}
