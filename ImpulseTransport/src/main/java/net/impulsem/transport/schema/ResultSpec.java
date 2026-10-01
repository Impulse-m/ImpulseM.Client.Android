package net.impulsem.transport.schema;


public final class ResultSpec {

    /** One of: object, list, bool, bool_overlay. */
    public String kind;
    public String typeName;
    public ParamSpec elem;
}
