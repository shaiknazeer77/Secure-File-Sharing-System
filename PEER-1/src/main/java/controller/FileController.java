package controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import model.Client;
import model.Crypto;
import model.CustomMultipartFile;
import model.Peer;
import model.ReceiveFile;
import model.Room;
import model.SendFile;
import model.UserFactory;
import model.myFile;
import service.DBService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class FileController {

    private final UserFactory f = new UserFactory();

    private Room room;
    private Client client;
    private Peer peer;
    private boolean isClient;
    private String mainPort;
    private String mainIP;
    // Base HTTP URL of the room creator, e.g. http://localhost:8001
    private String clink;
    private Crypto c;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Autowired
    private DBService fileService;

    private String normalizeBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Room link is empty. Create or join a room first.");
        }

        String url = value.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri = URI.create(url);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid room URL: " + value);
        }
        return url;
    }

    private String registerMyself(String myself, String link) throws IOException, InterruptedException {
        String baseUrl = normalizeBaseUrl(link);
        String encodedPeer = URLEncoder.encode(myself, StandardCharsets.UTF_8.name());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/register/" + encodedPeer))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Peer registration failed. HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        String key = response.body() == null ? "" : response.body().trim();
        if (key.isEmpty()) {
            throw new IOException("Peer registration returned an empty room key.");
        }
        return key;
    }

    private List<String> getAllPeers() throws IOException, InterruptedException {
        String baseUrl = normalizeBaseUrl(clink);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/getAllPeersFromClient"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Could not retrieve peers. HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        String body = response.body() == null ? "" : response.body().trim();
        if (body.length() < 2 || !body.startsWith("[") || !body.endsWith("]")) {
            if (body.isEmpty() || "[]".equals(body)) {
                return new ArrayList<>();
            }
            throw new IOException("Unexpected peer-list response: " + body);
        }

        body = body.substring(1, body.length() - 1).trim();
        List<String> peers = new ArrayList<>();
        if (!body.isEmpty()) {
            for (String entry : body.split(",")) {
                String address = entry.trim();
                if (!address.isEmpty()) {
                    peers.add(address);
                }
            }
        }
        return peers;
    }

    private RedirectView filesRedirect() {
        String host = (mainIP == null || mainIP.trim().isEmpty()) ? "localhost" : mainIP;
        String port = (mainPort == null || mainPort.trim().isEmpty()) ? "8001" : mainPort;
        RedirectView view = new RedirectView();
        view.setUrl("http://" + host + ":" + port + "/showAllFiles");
        view.setStatusCode(HttpStatus.SEE_OTHER);
        return view;
    }

    @GetMapping("/register/{peer:.+}")
    @ResponseBody
    public String register(@PathVariable("peer") String peerAddress) {
        if (room == null) {
            throw new IllegalStateException("No room exists on this server. Create a room first.");
        }
        room.addPeers(peerAddress);
        System.out.println("Registered peer: " + peerAddress + "; peers=" + room.getPeers());
        return room.getKey();
    }

    @GetMapping("/getAllPeersFromClient")
    @ResponseBody
    public String getAllPeersFromClientFunc() {
        if (room == null) {
            throw new IllegalStateException("No room exists on this server. Create a room first.");
        }
        return room.getPeers().toString();
    }

    @PostMapping("/send/{id}")
    public RedirectView sendFileFunc(@PathVariable String id) throws Exception {
        myFile file = fileService.getFile(id);
        if (file == null || file.getFile() == null) {
            throw new IOException("File not found or file contents are empty. ID: " + id);
        }
        if (c == null) {
            throw new IllegalStateException("Encryption is not initialized. Create or join a room first.");
        }

        List<String> peers = getAllPeers();
        SendFile sender = SendFile.getInstance();
        String myself = mainIP + ":" + mainPort;

        String sourceIP;
        String sourcePort;
        if (isClient) {
            if (client == null) {
                throw new IllegalStateException("Room creator client is not initialized.");
            }
            sourceIP = client.getIP();
            sourcePort = client.getPort();
        } else {
            if (peer == null) {
                throw new IllegalStateException("Peer is not initialized. Join the room first.");
            }
            sourceIP = peer.getIP();
            sourcePort = peer.getPort();
        }

        File tempFile = File.createTempFile("secure-share-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(tempFile)) {
                output.write(file.getFile());
            }

            byte[] fileBuffer = new byte[(int) tempFile.length()];
            try (FileInputStream input = new FileInputStream(tempFile)) {
                int offset = 0;
                while (offset < fileBuffer.length) {
                    int read = input.read(fileBuffer, offset, fileBuffer.length - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset != fileBuffer.length) {
                    throw new IOException("Could not read the complete temporary file.");
                }
            }

            String checksum = c.calculateChecksum(fileBuffer);
            String query = "IP=" + encode(sourceIP)
                    + "&port=" + encode(sourcePort)
                    + "&name=" + encode(file.getFilename())
                    + "&type=" + encode(file.getFileType())
                    + "&checksum=" + encode(checksum);

            boolean foundRecipient = false;
            for (String address : peers) {
                String peerAddress = address.trim();
                if (peerAddress.isEmpty() || peerAddress.equals(myself)) {
                    continue;
                }
                foundRecipient = true;

                HttpRequest receiveRequest = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + peerAddress + "/receiveFile?" + query))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                // Start the receiving endpoint request before sending bytes over the data socket.
                CompletableFuture<HttpResponse<String>> receiveResponse = httpClient.sendAsync(
                        receiveRequest, HttpResponse.BodyHandlers.ofString());

                if (isClient) {
                    sender.send(tempFile, client.getIP(), Integer.parseInt(client.getPort()), c);
                } else {
                    sender.send(tempFile, peer.getIP(), Integer.parseInt(peer.getPort()), c);
                }

                HttpResponse<String> result = receiveResponse.get();
                System.out.println("Receive endpoint for " + peerAddress + " returned HTTP "
                        + result.statusCode() + ": " + result.body());
            }

            if (!foundRecipient) {
                System.out.println("No other peers are registered in this room yet.");
            }
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }

        return filesRedirect();
    }

    private String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    @GetMapping("/receiveFile")
    public RedirectView receiveFileFunc(@RequestParam String IP,
                                        @RequestParam String port,
                                        @RequestParam String name,
                                        @RequestParam String type,
                                        @RequestParam String checksum) throws Exception {
        if (c == null) {
            throw new IllegalStateException("Encryption is not initialized. Join the room before receiving files.");
        }

        ReceiveFile receiver = ReceiveFile.getInstance();
        if (receiver == null) {
            throw new IllegalStateException("File receiver is not initialized.");
        }

        myFile output = receiver.receive(IP, Integer.parseInt(port), c, name, type, checksum);
        if (output == null || output.getFile() == null) {
            throw new IOException("The receiver returned no file data.");
        }

        CustomMultipartFile custom = new CustomMultipartFile(
                output.getFile(), output.getFilename(), output.getFileType(),
                (long) Integer.parseInt(output.getFileSize()));
        fileService.addFileCustom(custom);
        return filesRedirect();
    }

    @PostMapping("/createRoom")
    public String createRoomFunc(HttpServletRequest request, Model model, String nickname)
            throws JsonProcessingException, IOException, InterruptedException {
        fileService.deleteAll();
        isClient = true;
        clink = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        client = (Client) f.getObject(2, nickname, clink);
        client = new Client(nickname, clink);
        room = client.getRoom();
        peer = null;
        mainIP = "localhost";
        mainPort = String.valueOf(request.getLocalPort());
        c = new Crypto(room.getKey());
        room.addPeers(mainIP + ":" + mainPort);
        return "upload";
    }

    @PostMapping("/joinRoom")
    public String joinRoomFunc(HttpServletRequest request, Model model,
                               @RequestParam String nickname, @RequestParam String link)
            throws IOException, InterruptedException {
        fileService.deleteAll();
        clink = normalizeBaseUrl(link);
        isClient = false;
        room = null;
        peer = (Peer) f.getObject(1, nickname, null);
        client = null;
        mainIP = "localhost";
        mainPort = String.valueOf(request.getLocalPort());
        String key = registerMyself(mainIP + ":" + mainPort, clink);
        c = new Crypto(key);
        return "upload";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("Please choose a non-empty file to upload.");
        }
        fileService.addFile(file);
        return "upload";
    }

    @GetMapping("/getFiles")
    @ResponseBody
    public ResponseEntity<?> getFilesRoute() throws IOException {
        return new ResponseEntity<>(fileService.getFiles().toString(), HttpStatus.OK);
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable String id) throws IOException {
        fileService.deleteFile(id);
        return "upload";
    }

    @GetMapping("/showAllFiles")
    public String showAllFiles() {
        return "upload";
    }

    @PostMapping("/showAllFiles")
    public String showAllFilesPost() {
        return "upload";
    }

    @PostMapping("/download/{id}")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id) throws IOException {
        myFile loadFile = fileService.downloadFile(id);
        if (loadFile == null || loadFile.getFile() == null) {
            return ResponseEntity.notFound().build();
        }
        String contentType = (loadFile.getFileType() == null || loadFile.getFileType().trim().isEmpty())
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : loadFile.getFileType();
        String filename = (loadFile.getFilename() == null || loadFile.getFilename().trim().isEmpty())
                ? "download" : loadFile.getFilename().replace("\"", "");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new ByteArrayResource(loadFile.getFile()));
    }
}