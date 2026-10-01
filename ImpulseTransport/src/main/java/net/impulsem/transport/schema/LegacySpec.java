package net.impulsem.transport.schema;

import java.util.ArrayList;
import java.util.List;


/** A constructor or method id from an older layer whose name still exists in the current layer. */
public final class LegacySpec {

    public int id;
    public String name;
    public int layer;
    public boolean method;
    public List<ParamSpec> params = new ArrayList<ParamSpec>();
    public int targetId;
    public boolean alias;
    public boolean supported;
    public String problem;
}
