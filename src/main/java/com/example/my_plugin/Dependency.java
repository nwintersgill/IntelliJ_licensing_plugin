package com.example.my_plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;



public class Dependency {
    public String group;
    public String name;
    public String version;
    public List<License> licenses;

    public Dependency(String group, String name, String version, List<License> licenses) {
        this.group = group;
        this.name = name;
        this.version = version;
        this.licenses = licenses;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("group", this.group);
        obj.addProperty("name", this.name);
        obj.addProperty("version", this.version);
        JsonArray licensesArray = new JsonArray();
        for (License lic : this.licenses) {
            licensesArray.add(lic.toJson());
        }
        obj.add("licenses", licensesArray);
        return obj;
    }
}



