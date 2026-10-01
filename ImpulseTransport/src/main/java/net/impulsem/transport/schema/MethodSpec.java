package net.impulsem.transport.schema;

import java.util.ArrayList;
import java.util.List;


public final class MethodSpec {

    public int id;
    public String method;
    public String path;
    public List<ParamSpec> params = new ArrayList<ParamSpec>();
    public ResultSpec result;
}
