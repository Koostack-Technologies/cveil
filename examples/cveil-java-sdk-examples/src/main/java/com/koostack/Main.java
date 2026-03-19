package com.koostack;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.CveilClientOptions;
import com.koostack.cveil.sdk.CveilConfig;
import com.koostack.cveil.sdk.model.ConsentScope;
import com.koostack.cveil.sdk.model.GrantRequest;
import com.koostack.cveil.sdk.model.RegisterSubjectRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException {

        var client = CveilClient.create(new CveilClientOptions(
                new CveilConfig(
                        "https://consentveil.koostack.com",
                        "cveil_Xul7UgxKEsy14mKeY9u6BAOOzBYYbuyB3pFW7oM706O4KfskK",
                        "EU"
                )));

        //client.registerSubject(new RegisterSubjectRequest("kooKachoo", "kooKachoo"));
client.grant(new GrantRequest("kooKachoo", new ConsentScope("PII", "*", "*", "MARKETING"), LocalDateTime.now().plusHours(1L).toString()));
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