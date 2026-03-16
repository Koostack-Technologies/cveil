package com.koostack;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.CveilClientOptions;
import com.koostack.cveil.sdk.CveilConfig;
import com.koostack.cveil.sdk.model.RegisterSubjectRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException {

        var client = CveilClient.create(new CveilClientOptions(
                new CveilConfig(
                        "http://localhost:8080",
                        "cveil_XMuJEsHentdX4IfIhqkfYhJkH2uuRS75mL9brXdogYv1xKQhK",
                        "EU"
                ),
                Path.of("wasm/cveil_sdk_core.wasm")
        ));

        client.registerSubject(new RegisterSubjectRequest("koo", "koo"));

// Mask a user record based on consent graph
//        var result = client.mask("user-123", "User", Map.of(
//                "email", "alice@example.com",
//                "name", "Alice",
//                "phone", "555-1234"
//        ), "Marketing");
//
//        System.out.println(result.data());
    }
}