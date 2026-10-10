package com.datascraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DumpHtml {
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        String username = dotenv.get("BNI_USERNAME");
        String password = dotenv.get("BNI_PASSWORD");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            
            page.navigate("https://www.bniconnectglobal.com/login/?message=");
            page.getByPlaceholder("Username").fill(username);
            page.getByPlaceholder("Password").fill(password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click();
            
            page.waitForURL("**/web/dashboard**");
            page.waitForLoadState(LoadState.NETWORKIDLE);
            
            page.getByLabel("Search People").getByRole(AriaRole.IMG).click();
            page.waitForTimeout(1000);
            
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Filter")).click();
            page.getByPlaceholder("Search Category").fill("legal");
            page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("Legal & Accounting Accounting")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Search Members")).click();
            
            page.waitForTimeout(4000); // Wait for results to load
            
            // Save the HTML to a file
            String html = page.content();
            Files.write(Paths.get("results.html"), html.getBytes());
            System.out.println("HTML saved to results.html");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
