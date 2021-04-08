package org.worker.litespring.v1;

import org.worker.litespring.v1.annotation.HnController;
import org.worker.litespring.v1.annotation.HnRequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@HnController("/demo")
public class DemoController {

    @HnRequestMapping("/hello")
    public void hello(HttpServletRequest request, HttpServletResponse response) {
        try {
            PrintWriter writer = response.getWriter();
            writer.println("demo hello");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
