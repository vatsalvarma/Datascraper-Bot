package com.datascraper;

import com.microsoft.playwright.CLI;

public class Record {
    public static void main(String[] args) {
        System.out.println("Opening Playwright Recorder...");
        try {
            // This will open a browser and record your actions into Java code!
            CLI.main(new String[]{"codegen", "https://www.bniconnectglobal.com/"});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
