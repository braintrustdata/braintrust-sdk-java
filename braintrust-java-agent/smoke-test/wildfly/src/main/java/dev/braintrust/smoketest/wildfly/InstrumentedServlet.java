package dev.braintrust.smoketest.wildfly;

import dev.braintrust.InstrumentationReflection;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Simple servlet that returns whether the Braintrust agent has instrumented the app.
 */
@WebServlet("/instrumented")
public class InstrumentedServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        resp.getWriter().print(InstrumentationReflection.isInstrumented());
    }
}
