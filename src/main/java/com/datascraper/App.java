package com.datascraper;

import io.github.cdimascio.dotenv.Dotenv;

public class App {
    public static void main(String[] args) {
        System.out.println("Starting BNI Data Scraper...");
        
        // 1. Load environment variables
        Dotenv dotenv = Dotenv.load();
        String username = dotenv.get("BNI_USERNAME");
        String password = dotenv.get("BNI_PASSWORD");
        
        String spreadsheetId = dotenv.get("GOOGLE_SHEET_ID");
        
        if (username == null || username.contains("your_username")) {
            System.err.println("Please set your BNI credentials in the .env file.");
            return;
        }
        
        if (spreadsheetId == null || spreadsheetId.contains("your_google_sheet_id")) {
            System.err.println("Please set your GOOGLE_SHEET_ID in the .env file. It is the long random text in your Google Sheets URL.");
            return;
        }

        // 2. Initialize the Scraper Service with the Spreadsheet ID
        ScraperService scraper = new ScraperService(spreadsheetId);
        
        // 3. Start Scraping Flow
        try {
            System.out.println("Logging into BNI Connect...");
            scraper.loginAndScrape(username, password);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
