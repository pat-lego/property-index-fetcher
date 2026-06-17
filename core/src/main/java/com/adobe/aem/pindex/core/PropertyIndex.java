package com.adobe.aem.pindex.core;

import java.util.List;

import lombok.Getter;

@Getter
public class PropertyIndex {

    private String index;
    private List<String> propertyNames;

    public PropertyIndex(String index, List<String> propertyNames) {
        this.index = index;
        this.propertyNames = propertyNames;
    }

}
