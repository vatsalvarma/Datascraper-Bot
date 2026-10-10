package com.datascraper;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;

public class GoogleSheetsHelper {
    private Sheets sheetsService;
    private String spreadsheetId;

    public GoogleSheetsHelper(String spreadsheetId) {
        this.spreadsheetId = spreadsheetId;
        try {
            String credPath = new File("credentials.json.json").exists() ? "credentials.json.json" : "credentials.json";
            GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credPath))
                    .createScoped(Collections.singletonList(SheetsScopes.SPREADSHEETS));
            
            this.sheetsService = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("BNI Scraper")
                    .build();
            System.out.println("Google Sheets API initialized successfully!");
        } catch (Exception e) {
            System.err.println("Failed to initialize Google Sheets. Check your credentials.json file.");
        }
    }

    private java.util.Set<String> existingSheets = null;

    public boolean addSheetIfNotExists(String sheetTitle) {
        if (sheetsService == null) return true;
        try {
            if (existingSheets == null) {
                existingSheets = new java.util.HashSet<>();
                com.google.api.services.sheets.v4.model.Spreadsheet spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute();
                for (com.google.api.services.sheets.v4.model.Sheet sheet : spreadsheet.getSheets()) {
                    existingSheets.add(sheet.getProperties().getTitle());
                }
            }
            
            if (existingSheets.contains(sheetTitle)) {
                return false;
            }
            
            // Create new sheet
            com.google.api.services.sheets.v4.model.AddSheetRequest addSheetRequest = new com.google.api.services.sheets.v4.model.AddSheetRequest().setProperties(new com.google.api.services.sheets.v4.model.SheetProperties().setTitle(sheetTitle));
            com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest batchRequest = new com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest()
                    .setRequests(Collections.singletonList(new com.google.api.services.sheets.v4.model.Request().setAddSheet(addSheetRequest)));
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchRequest).execute();
            System.out.println("Created new sheet tab: " + sheetTitle);
            
            // Add header row
            List<Object> headers = Arrays.asList("Name", "Phone", "Email", "Website", "Social", "Address", "Keywords", "Category");
            appendData(sheetTitle, headers);
            
            existingSheets.add(sheetTitle);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to check/create sheet tab: " + sheetTitle);
            return true; // proceed anyway if error
        }
    }

    public void appendData(String sheetTitle, List<Object> rowData) {
        appendDataBatch(sheetTitle, Collections.singletonList(rowData));
    }

    public void appendDataBatch(String sheetTitle, List<List<Object>> rowDataBatch) {
        if (sheetsService == null || rowDataBatch.isEmpty()) return;
        try {
            com.google.api.services.sheets.v4.model.ValueRange body = new com.google.api.services.sheets.v4.model.ValueRange().setValues(rowDataBatch);
            sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "'" + sheetTitle + "'!A1", body)
                    .setValueInputOption("USER_ENTERED")
                    .execute();
        } catch (Exception e) {
            System.err.println("Failed to append data to Google Sheets.");
            e.printStackTrace();
        }
    }
}
