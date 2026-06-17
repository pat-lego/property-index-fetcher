package com.adobe.aem.pindex.core;

import java.util.List;

import lombok.Getter;

@Getter
public class NodeTypeIndex {

    private String index;
    private List<String> declaringNodeTypes;

    public NodeTypeIndex(String index, List<String> declaringNodeTypes) {
        this.index = index;
        this.declaringNodeTypes = declaringNodeTypes;
    }

}
