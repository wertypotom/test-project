package com.example.demo;

import java.util.ArrayList;
import java.util.List;

public class Service {
    private String id;

    private List<String> names;

    public Service() {
        names = new ArrayList<>();
        names.add("Mother");
        names.add("Father");
        names.add("Son");
    }
}
