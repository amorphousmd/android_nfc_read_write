package com.example.myapplication;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.MifareUltralight;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.ArrayAdapter;


public class MainActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private String[][] techLists;
    private EditText nfcDataText;
    private EditText nfcWriteText;
    private Button writeButton;
    private Spinner writePageSpinner;
    private Tag currentTag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No one like the title bar.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                                  WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        // Initialize the text box
        nfcDataText = findViewById(R.id.nfc_read_text);

        // Initialize the write box
        nfcWriteText = findViewById(R.id.nfc_write_text);

        // Initialize the write button
        writeButton = findViewById(R.id.write_button);
        writeButton.setEnabled(false);
        writeButton.setOnClickListener(v -> writeToTag());

        // Initialize write spinner
        writePageSpinner = findViewById(R.id.write_page_options);

        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC is not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable NFC in device settings", Toast.LENGTH_LONG).show();
        }

        // Setup NFC intent handling
        setupNfcIntent();

        // Check if app was launched by NFC intent
        handleIntent(getIntent());
    }

    private void setupNfcIntent() {
        pendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE
        );

        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        try {
            ndef.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("Failed to add MIME type.", e);
        }

        intentFilters = new IntentFilter[] { ndef };
        techLists = new String[][] {
                new String[] { Ndef.class.getName() },
                new String[] { MifareUltralight.class.getName() }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                currentTag = tag;
                writeButton.setEnabled(true);
                readNfcTag(tag, intent);
            }
        }
    }

    private void readNfcTag(Tag tag, Intent intent) {
        StringBuilder data = new StringBuilder();

        // Get tag ID
        byte[] id = tag.getId();
        data.append("Tag ID: ").append(bytesToHex(id)).append("\n\n");

        // Try to read NDEF data
        Parcelable[] rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (rawMessages != null) {
            data.append("NDEF Data:\n");
            for (Parcelable rawMessage : rawMessages) {
                NdefMessage message = (NdefMessage) rawMessage;
                NdefRecord[] records = message.getRecords();

                for (NdefRecord record : records) {
                    String payload = readTextRecord(record);
                    if (payload != null) {
                        data.append("Text: ").append(payload).append("\n");
                    } else {
                        data.append("Raw payload: ").append(bytesToHex(record.getPayload())).append("\n");
                    }
                }
            }
        } else {
            // Try to read using Ndef tech
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                try {
                    ndef.connect();
                    NdefMessage message = ndef.getNdefMessage();
                    if (message != null) {
                        data.append("NDEF Data:\n");
                        for (NdefRecord record : message.getRecords()) {
                            String payload = readTextRecord(record);
                            if (payload != null) {
                                data.append("Text: ").append(payload).append("\n");
                            } else {
                                data.append("Raw payload: ").append(bytesToHex(record.getPayload())).append("\n");
                            }
                        }
                    }
                    ndef.close();
                } catch (Exception e) {
                    data.append("Error reading NDEF: ").append(e.getMessage()).append("\n");
                }
            } else {
                data.append("No NDEF data found\n");
            }
        }

        // Display available technologies
        String[] techList = tag.getTechList();
        data.append("\nSupported Technologies:\n");
        for (String tech : techList) {
            data.append("- ").append(tech.substring(tech.lastIndexOf('.') + 1)).append("\n");
        }

        readMifareUltralightPages(tag, data);

        // Update the text box with the read data
        nfcDataText.setText(data.toString());

        Toast.makeText(this, "NFC tag read successfully!", Toast.LENGTH_SHORT).show();
    }

    private String readTextRecord(NdefRecord record) {
        if (record.getTnf() != NdefRecord.TNF_WELL_KNOWN) {
            return null;
        }

        if (!java.util.Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {
            return null;
        }

        try {
            byte[] payload = record.getPayload();
            String textEncoding = ((payload[0] & 128) == 0) ? "UTF-8" : "UTF-16";
            int languageCodeLength = payload[0] & 63;
            return new String(payload, languageCodeLength + 1,
                    payload.length - languageCodeLength - 1, textEncoding);
        } catch (Exception e) {
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private void writeToTag() {
        if (currentTag == null) {
            Toast.makeText(this, "No tag detected. Please scan a tag first.", Toast.LENGTH_SHORT).show();
            return;
        }

        MifareUltralight ultralight = MifareUltralight.get(currentTag);
        if (ultralight == null) {
            Toast.makeText(this, "This tag doesn't support MifareUltralight writing", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            ultralight.connect();

            // Write bytes 1, 2, 3, 4 to selected page
            String selectPage = writePageSpinner.getSelectedItem().toString();
            String selectedPageIndex = selectPage.replace("Page", "").strip();
            int pageIndex = Integer.parseInt(selectedPageIndex);
            byte[] dataToWrite = prepareWriteData(nfcWriteText.getText().toString());
            ultralight.writePage(pageIndex, dataToWrite);

            ultralight.close();

            Toast.makeText(this, "Successfully wrote 1,2,3,4 to page " + pageIndex, Toast.LENGTH_SHORT).show();

            // Re-read the tag to show updated data
            if (currentTag != null) {
                readNfcTag(currentTag, getIntent());
            }

        } catch (Exception e) {
            Toast.makeText(this, "Write failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readMifareUltralightPages(Tag tag, StringBuilder data) {
        MifareUltralight ultralight = MifareUltralight.get(tag);
        if (ultralight != null) {
            try {
                ultralight.connect();
                data.append("\nMifare Ultralight Memory Pages (4-13):\n");

                // Read 200 pages. Arbitrary large number, I happen to know NTAG215 have 136 pages.
                for (int page = 0; page < 200; page++) {
                    try {
                        byte[] pageData = ultralight.readPages(page);
                        // readPages returns 4 pages (16 bytes), but we only want the first page (4 bytes)
                        byte[] singlePage = new byte[4];
                        System.arraycopy(pageData, 0, singlePage, 0, 4);

                        data.append("Page ").append(page).append(": ");
                        data.append(bytesToHex(singlePage));

                        // Try to interpret as ASCII text
                        String asciiText = bytesToAscii(singlePage);
                        if (!asciiText.trim().isEmpty()) {
                            data.append(" (").append(asciiText).append(")");
                        }
                        data.append("\n");
                    } catch (Exception e) {
                        Toast.makeText(this, "Read "+ page + " pages", Toast.LENGTH_SHORT).show();
                        updateSpinner(page);
                        break;
                    }
                }
                ultralight.close();
            } catch (Exception e) {
                data.append("\nError connecting to MifareUltralight: ").append(e.getMessage()).append("\n");
            }
        } else {
            data.append("\nThis tag does not support MifareUltralight technology\n");
        }
    }

    private void updateSpinner(int maxPage) {
        // Create array of page options
        String[] pageOptions = new String[maxPage - 3]; // Pages 4 to N
        for (int i = 0; i < pageOptions.length; i++) {
            pageOptions[i] = "Page " + (i + 4); // Start from Page 4
        }

        // Create adapter and set it to spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                pageOptions
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        writePageSpinner.setAdapter(adapter);
    }

    private byte[] prepareWriteData(String input) {
        byte[] result = new byte[4]; // Always 4 bytes, initialized to 0x00

        if (input == null || input.isEmpty()) {
            // If empty, all bytes are 0x00 (already default for new byte array)
            return result;
        }

        // Convert string to ASCII bytes
        int copyLength = Math.min(input.length(), 4);
        for (int i = 0; i < copyLength; i++) {
            char c = input.charAt(i);
            if (c <= 127) { // Valid ASCII range
                result[i] = (byte) c;
            } else {
                result[i] = 0x3F; // '?' for non-ASCII characters
            }
        }

        return result;
    }

    private String bytesToAscii(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (b >= 32 && b <= 126) { // Printable ASCII range
                sb.append((char) b);
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }
}