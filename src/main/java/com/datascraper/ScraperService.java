package com.datascraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class ScraperService {

    private final String url;
    private final GoogleSheetsHelper sheetsHelper;

    public ScraperService(String spreadsheetId) {
        Dotenv dotenv = Dotenv.load();
        this.url = dotenv.get("BNI_URL", "https://www.bniconnectglobal.com/login/?message=");
        this.sheetsHelper = new GoogleSheetsHelper(spreadsheetId);
    }

    public void loginAndScrape(String username, String password) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            BrowserContext context = browser.newContext();
            
            // ULTRA FAST MODE: Block all images, fonts, and media to save bandwidth and memory!
            context.route("**/*", route -> {
                String type = route.request().resourceType();
                if ("image".equals(type) || "media".equals(type) || "font".equals(type)) {
                    route.abort();
                } else {
                    route.resume();
                }
            });
            
            Page page = context.newPage();
            
            System.out.println("Navigating to login page...");
            page.navigate(this.url);
            
            System.out.println("Logging in...");
            page.getByPlaceholder("Username").click();
            page.getByPlaceholder("Username").fill(username);
            page.getByPlaceholder("Password").click();
            page.getByPlaceholder("Password").fill(password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click();
            
            System.out.println("Waiting for dashboard...");
            page.waitForURL("**/web/dashboard**");
            page.waitForLoadState(LoadState.NETWORKIDLE);
            
            System.out.println("Opening Search Filter...");
            page.getByLabel("Search People").getByRole(AriaRole.IMG).click();
            page.waitForTimeout(1000);
            if (page.getByPlaceholder("Search Category").count() == 0 || !page.getByPlaceholder("Search Category").isVisible()) {
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Filter")).click();
                page.waitForTimeout(1000);
            }
            
            System.out.println("Typing 'legal' to fetch all categories...");
            page.getByPlaceholder("Search Category").click();
            page.getByPlaceholder("Search Category").fill("legal");
            page.waitForTimeout(2000);
            
            Locator options = page.getByRole(AriaRole.OPTION);
            int categoryCount = options.count();
            if (categoryCount == 0) {
                System.out.println("No categories found.");
                return;
            }
            System.out.println("Found " + categoryCount + " categories to scrape!");
            
            List<String> allCategories = new ArrayList<>();
            for (int i = 0; i < categoryCount; i++) {
                allCategories.add(options.nth(i).innerText().trim());
            }
            
            page.keyboard().press("Escape");
            page.waitForTimeout(1000);
            
            boolean didSearch = false;
            
            // Loop through every category using the pre-fetched list
            for (int c = 0; c < allCategories.size(); c++) {
                String categoryName = allCategories.get(c);
                
                // Google Sheets tabs can't have certain characters, and special characters like '&' break the API URL.
                String cleanCategoryName = categoryName.replaceAll("[^a-zA-Z0-9 -]", "").replaceAll("\\s+", " ").trim();
                if (cleanCategoryName.length() > 100) cleanCategoryName = cleanCategoryName.substring(0, 100);
                
                System.out.println("\n--- SCAPING CATEGORY: " + cleanCategoryName + " ---");
                
                // Create a tab in Google Sheets for this category!
                boolean isNewSheet = sheetsHelper.addSheetIfNotExists(cleanCategoryName);
                if (!isNewSheet) {
                    System.out.println("Category already exists in Google Sheets! Skipping to resume...");
                    continue;
                }
                
                if (didSearch) {
                    System.out.println("Refreshing search page to clear previous filters...");
                    page.navigate("https://www.bniconnectglobal.com/web/dashboard/search");
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    page.waitForTimeout(2000);
                    if (page.getByPlaceholder("Search Category").count() == 0 || !page.getByPlaceholder("Search Category").isVisible()) {
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Filter")).click();
                        page.waitForTimeout(1000);
                    }
                }
                
                page.getByPlaceholder("Search Category").click();
                page.getByPlaceholder("Search Category").fill("legal");
                page.waitForTimeout(1500);
                
                page.getByRole(AriaRole.OPTION).nth(c).click();
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Search Members")).click();
                didSearch = true;
                
                System.out.println("Waiting for search results to load...");
                page.waitForTimeout(4000); 
                
                Set<String> scrapedNames = new HashSet<>();
                boolean foundNew = true;
                
                while (foundNew) {
                    foundNew = false;
                    List<List<Object>> pageData = new ArrayList<>();
                    
                    // FAST MODE: Get all names via JS to avoid O(N^2) Playwright DOM queries
                    @SuppressWarnings("unchecked")
                    List<String> visibleNames = (List<String>) page.evaluate(
                        "Array.from(document.querySelectorAll(\"a[href*='networkHome?userId='][aria-label]\")).map(a => a.getAttribute('aria-label'))"
                    );
                    
                    List<String> newNames = new ArrayList<>();
                    for (String nameText : visibleNames) {
                        if (nameText != null && !scrapedNames.contains(nameText)) {
                            newNames.add(nameText);
                            scrapedNames.add(nameText);
                            foundNew = true;
                        }
                    }
                    
                    // CLOUDFLARE STEALTH MODE: Process sequentially (1 by 1) to avoid triggering Cloudflare Error 1015
                    int batchSize = 1;
                    for (int i = 0; i < newNames.size(); i += batchSize) {
                        int end = Math.min(i + batchSize, newNames.size());
                        List<String> batch = newNames.subList(i, end);
                        
                        List<Page> popups = new ArrayList<>();
                        List<String> popupNames = new ArrayList<>();
                        
                        for (String nameText : batch) {
                            try {
                                Locator memberToClick = page.locator("a[href*='networkHome?userId='][aria-label='" + nameText.replace("'", "\\'") + "']").first();
                                try { memberToClick.scrollIntoViewIfNeeded(); } catch (Exception e) {}
                                
                                Page p = page.waitForPopup(new Page.WaitForPopupOptions().setTimeout(10000), () -> {
                                    memberToClick.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                                });
                                popups.add(p);
                                popupNames.add(nameText);
                                
                                // STAGGER requests by 1.5 seconds to act exactly like a human reading profiles!
                                page.waitForTimeout(1500);
                            } catch (Exception e) {
                                System.out.println("  -> Failed to open popup for: " + nameText);
                            }
                        }
                        
                        // Extract data from the loaded popups!
                        for (int pIdx = 0; pIdx < popups.size(); pIdx++) {
                            Page popup = popups.get(pIdx);
                            String nameText = popupNames.get(pIdx);
                            
                            try {
                                popup.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(15000));
                                String popupText = popup.locator("body").innerText();
                                
                                if (popupText.contains("Error 1015") || popupText.contains("rate limited")) {
                                    System.out.println("🚨 CLOUDFLARE BAN DETECTED! Pausing the robot for 10 minutes to let the temporary ban expire...");
                                    page.waitForTimeout(600000); // Wait 10 minutes
                                    continue; // Skip this one, it failed
                                }
                                
                                // Extract fields by parsing lines
                                String[] lines = popupText.split("\\r?\\n");
                                List<String> phonesList = new ArrayList<>();
                                String email = "";
                                List<String> websitesList = new ArrayList<>();
                                List<String> socialsList = new ArrayList<>();
                                String address = "";
                                String keywords = "";

                                boolean inPersonal = false;
                                boolean inProfessional = false;
                                String lastProfessionalLine = "";

                                for (String line : lines) {
                                    line = line.trim();
                                    if (line.isEmpty()) continue;
                                    
                                    if (line.equals("Personal Details")) { inPersonal = true; inProfessional = false; continue; }
                                    if (line.equals("City") || line.equals("My Bio") || line.equals("Professional Details") || line.equals("Training History")) { 
                                        if (line.equals("Professional Details")) { inProfessional = true; } else { inProfessional = false; }
                                        inPersonal = false; 
                                    }
                                    
                                    if (line.matches("^\\+?[0-9\\s\\-()]{10,25}$")) {
                                        String cleanPhone = line.replaceAll("[^0-9+]", "");
                                        if (cleanPhone.length() >= 9 && !phonesList.contains(cleanPhone)) phonesList.add(cleanPhone);
                                    } 
                                    else if (line.matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
                                        if (email.isEmpty()) email = line;
                                    }
                                    else if (line.startsWith("http")) {
                                        String lower = line.toLowerCase();
                                        if (lower.contains("facebook.com") || lower.contains("instagram.com") || lower.contains("linkedin.com") || lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("youtube.com")) {
                                            if (!socialsList.contains(line)) socialsList.add(line);
                                        } else if (!line.contains("bniconnectglobal.com")) {
                                            if (!websitesList.contains(line)) websitesList.add(line);
                                        }
                                    }
                                    else if (inPersonal) {
                                        if (line.length() > 15 && !line.equals("Personal Details") && !line.equals("City") && !line.equals("Zip / Postal Code")) {
                                            address = line; 
                                        }
                                    }
                                    else if (inProfessional) {
                                        lastProfessionalLine = line; 
                                    }
                                }

                                keywords = lastProfessionalLine;

                                String phone = String.join(", ", phonesList);
                                if (phone.startsWith("+") || phone.startsWith("=")) {
                                    phone = "'" + phone;
                                }
                                
                                String website = String.join(", ", websitesList);
                                String social = String.join(", ", socialsList);
                                
                                System.out.println("  -> Extracted: " + nameText + " | " + phone + " | " + email);
                                
                                // Save this data to batch
                                List<Object> rowData = java.util.Arrays.asList(
                                    nameText,
                                    phone,
                                    email,
                                    website,
                                    social,
                                    address,
                                    keywords,
                                    cleanCategoryName
                                );
                                pageData.add(rowData);
                                
                            } catch (Exception ex) {
                                System.out.println("  -> Error reading member card: " + nameText + " - " + ex.getMessage());
                            } finally {
                                try { popup.close(); } catch (Exception e) {}
                            }
                        }
                    }
                    
                    if (!pageData.isEmpty()) {
                        System.out.println("Saving batch of " + pageData.size() + " members to Google Sheets...");
                        sheetsHelper.appendDataBatch(cleanCategoryName, pageData);
                    }
                    
                    if (foundNew) {
                        // Attempt to load more (Infinite Scroll)
                        try {
                            Locator memberLinks = page.locator("a[href*='networkHome?userId='][aria-label]");
                            if (memberLinks.count() > 0) {
                                memberLinks.last().scrollIntoViewIfNeeded();
                                page.waitForTimeout(2000);
                            }
                        } catch (Exception e) {}
                        
                        // Attempt to load more (Pagination)
                        try {
                            Locator nextBtn = page.locator("button[aria-label*='Next'], button[aria-label*='next'], button[title*='Next'], button[title*='next']");
                            for (int j = 0; j < nextBtn.count(); j++) {
                                Locator btn = nextBtn.nth(j);
                                String ariaDisabled = btn.getAttribute("aria-disabled");
                                String cssClass = btn.getAttribute("class");
                                boolean isDisabled = btn.isDisabled() || "true".equals(ariaDisabled) || (cssClass != null && cssClass.toLowerCase().contains("disabled"));
                                
                                if (btn.isVisible() && !isDisabled) {
                                    System.out.println("Navigating to next page...");
                                    btn.click();
                                    page.waitForTimeout(4000);
                                    break;
                                }
                            }
                        } catch (Exception e) {}
                    }
                }
            }
            
            System.out.println("SCRAPING COMPLETE!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Helper method to extract data using Regex
    private String extractRegex(String text, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }
}
