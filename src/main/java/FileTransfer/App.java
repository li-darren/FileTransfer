/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package FileTransfer;

import com.jcraft.jsch.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.imgscalr.Scalr;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class App extends Application {

    public final static boolean DEBUGGING = true;
    private FileSender fileSender = null;
    private RemoteTerminal remoteTerminal = null;
    private TextField currentUrl = null;
    private TextField commandInputBox = null;
    private TextArea terminalOutput = null;
    private ListView<FileInfo> folderItems = null;
    private String saveLoc = null;
    private FileMonitor fileMonitor = null;
    private final static String linkIconKey = ";/link/";

    public static final int iconSize = 16;

    @Override
    public void start(Stage primaryStage) throws Exception {
        BorderPane root = new BorderPane();

        VBox topBar = new VBox();
        currentUrl = new TextField("/");

        currentUrl.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                if (DEBUGGING){
                    System.out.println("Changing directory from url bar....");
                }
                setRemoteDirectoryToUrlBar();
                updateUrlBarAndDirectories();
            }
        });

        MenuBar menuBar = new MenuBar();
        Menu menuFile = new Menu("File");
        menuBar.getMenus().addAll(menuFile);

        topBar.getChildren().addAll(menuBar, currentUrl);

        root.setTop(topBar);

        commandInputBox = new TextField();
        commandInputBox.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                System.err.println(String.format("Printing text: %s", commandInputBox.getText()));
                remoteTerminal.sendCommand(commandInputBox.getText());
                commandInputBox.setText("");
                updateUrlBarAndDirectories();
            }
        });

        terminalOutput = new TextArea();

        VBox terminalGroup = new VBox();
        terminalGroup.getChildren().addAll(terminalOutput, commandInputBox);

        root.setBottom(terminalGroup);

        folderItems = new ListView();

        folderItems.setOnDragOver(new EventHandler<DragEvent>() {
            @Override
            public void handle(DragEvent event) {
                if (event.getGestureSource() != folderItems && event.getDragboard().hasFiles()){
                    event.acceptTransferModes(TransferMode.ANY);
                }
                event.consume();
            }
        });

        folderItems.setOnDragDropped(new EventHandler<DragEvent>() {
            @Override
            public void handle(DragEvent event) {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasFiles()){
                    success = true;

                    String draggedInto = fileSender.getRemotePath();
                    if (DEBUGGING){
                        System.out.println(String.format("Dragged into root folder view: %s", draggedInto));
                    }

                    for (File file : db.getFiles()){
                        if (App.DEBUGGING){
                            System.out.println(String.format("Dragged file into folderview %s", file.toString()));
                        }
                        try {
                            uploadFileRecursively(file.toPath(), draggedInto);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                event.setDropCompleted(success);

                updateUrlBarAndDirectories();

                event.consume();
            }
        });

        folderItems.setOnKeyPressed(new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                FileInfo selectedFileInfo = folderItems.getSelectionModel().getSelectedItem();
                if (event.getCode() == KeyCode.ENTER){
                    onFileInfoOpened(selectedFileInfo);
                }
                else if (event.getCode() == KeyCode.DELETE){
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("Confirm Delete?");
                    alert.setHeaderText(null);
                    alert.setContentText(String.format("Are you sure you want to delete: \"%s\"?", selectedFileInfo.getFileName()));
                    ((Button) alert.getDialogPane().lookupButton(ButtonType.OK)).setText("Yes");

                    Optional<ButtonType> result = alert.showAndWait();
                    if (result.get() == ButtonType.OK) {
                        try {
                            if (selectedFileInfo.getSftpATTRS().isDir()){
                                fileSender.removeAllFolderContents(selectedFileInfo.getFileName());
                            }
                            else{
                                fileSender.removeFile(selectedFileInfo.getFileName());
                            }
                        } catch (SftpException e) {
                            e.printStackTrace();
                        }
                        updateUrlBarAndDirectories();
                    }
                }
                else if (event.getCode() == KeyCode.F2){
                    TextInputDialog dialog = new TextInputDialog(selectedFileInfo.getFileName());
                    dialog.setTitle("Rename Folder");
                    dialog.setHeaderText(null);
                    dialog.setContentText(String.format("Rename folder \"%s\" to:", selectedFileInfo.getFileName()));

                    Optional<String> result = dialog.showAndWait();
                    if (result.isPresent()){
                        String oldPath = fileSender.getRemotePath() + "/" + selectedFileInfo.getFileName();
                        String newPath = fileSender.getRemotePath() + "/" + result.get();
                        try {
                            fileSender.rename(oldPath, newPath);
                        } catch (SftpException e) {
                            e.printStackTrace();
                            //todo: tell user it failed to configure
                        }
                    }

                    updateUrlBarAndDirectories();
                }
            }
        });

        folderItems.setCellFactory(lv -> new ListCell<FileInfo>(){
            @Override
            public void updateItem(FileInfo entry, boolean empty){
                super.updateItem(entry, empty);

                getStylesheets().clear();
                String style = getClass().getClassLoader().getResource("ListCellStyling/ListCellStyling.css").toExternalForm();
                getStylesheets().add(style);

                if (empty){
                    setText(null);
                    setGraphic(null);
                }
                else{
                    Image image;

                    if (entry.getSftpATTRS() == null || entry.getSftpATTRS().isDir()){
                        image = IconFetcher.getFileIcon(".", ".");
                    }
                    else if (entry.getSftpATTRS().isLink()){
                        image = IconFetcher.getFileIcon("", ":link_icon");
                    }
                    else{
                        String fileName = entry.getFileName();
                        final String extension = IconFetcher.getFileExtension(fileName);
                        image = IconFetcher.getFileIcon(fileName, extension);
                    }

                    setGraphic(new ImageView(image));
                    setText(entry.getFileName());

                }

                ListCell currentListCell = this;

                setOnMouseClicked(mouseClickedEvent -> {
                    if (!empty){
                        if (mouseClickedEvent.getButton().equals(MouseButton.PRIMARY) && mouseClickedEvent.getClickCount() == 2) {
                            onFileInfoOpened(entry);
                        }
                    }
                });

                //////////////////////
                //DRAG FROM EXTERNAL INTO APPLICATION
                //////////////////////

                setOnDragEntered(new EventHandler<DragEvent>() {
                    @Override
                    public void handle(DragEvent event) {
                        if (!empty){
                            if (event.getGestureSource() != currentListCell && event.getDragboard().hasFiles()){
                                getStylesheets().clear();
                                String style = getClass().getClassLoader().getResource("ListCellStyling/ListCellStylingDragOver.css").toExternalForm();
                                getStylesheets().add(style);
                                setTextFill(Color.BLACK);
                            }
                            event.consume();
                        }
                    }
                });

                setOnDragExited(new EventHandler<DragEvent>() {
                    @Override
                    public void handle(DragEvent event) {
                        if (!empty){
                            if (event.getGestureSource() != currentListCell){
                                getStylesheets().clear();
                                String style = getClass().getClassLoader().getResource("ListCellStyling/ListCellStyling.css").toExternalForm();
                                getStylesheets().add(style);
                                setTextFill(Color.BLACK);
                            }
                            event.consume();
                        }
                    }
                });

                setOnDragOver(new EventHandler<DragEvent>() {
                    @Override
                    public void handle(DragEvent event) {
                        if (!empty){
                            if (event.getGestureSource() != currentListCell && event.getDragboard().hasFiles() && FileInfo.isDirectoryOrLink(entry.getSftpATTRS())){
                                event.acceptTransferModes(TransferMode.ANY);
                            }
                            event.consume();
                        }
                    }
                });

                setOnDragDropped(new EventHandler<DragEvent>() {
                    @Override
                    public void handle(DragEvent event) {
                        if (!empty){
                            Dragboard db = event.getDragboard();
                            boolean success = false;
                            if (db.hasFiles()){
                                success = true;

                                String draggedInto = fileSender.getRemotePath().concat("/").concat(entry.getFileName());
                                if (DEBUGGING){
                                    System.out.println(String.format("Dragged into folder in cell: %s", draggedInto));
                                }

                                for (File file : db.getFiles()){
                                    try {
                                        uploadFileRecursively(file.toPath(), draggedInto);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }

                            event.setDropCompleted(success);
                            event.consume();
                        }
                    }
                });

                //////////////////////
                //DRAG FROM APPLICATION TO EXTERNAL
                //////////////////////

                setOnDragDetected(new EventHandler<MouseEvent>() {
                    @Override
                    public void handle(MouseEvent event) {
                        if (!empty){
                            if (!entry.getSftpATTRS().isLink()){
                                Dragboard db = startDragAndDrop(TransferMode.COPY);

                                ClipboardContent content = new ClipboardContent();
                                try {

                                    File localFileLocation = downloadFileLocally(entry.getFileName(), entry.getSftpATTRS().isDir());

                                    System.out.println(String.format("Downloaded File Location: %s", localFileLocation.getPath()));
                                    content.putFiles(new ArrayList<>(Arrays.asList(localFileLocation)));
                                    content.putString(getText());
                                    db.setContent(content);
                                    if (DEBUGGING) {
                                        System.out.println(String.format("Dragging %s", getText()));
                                    }
                                }
                                catch(SftpException e){
                                    e.printStackTrace();
                                }

                                event.consume();
                            }
                        }
                    }
                });

                setOnDragDone(new EventHandler<DragEvent>() {
                    @Override
                    public void handle(DragEvent event) {
                        if (!empty){
                            System.out.println(String.format("Drag Done: %s", event.getTarget()));
                        }
                    }
                });
            }
        });

        root.setCenter(folderItems);

        primaryStage.setHeight(700);
        primaryStage.setWidth(1000);
        primaryStage.setScene(new Scene(root));
        primaryStage.setTitle("File Transfer");
        primaryStage.show();

//        SSHSessionCredentials credentials = null;
//
//        while (credentials == null || isInvalidCredentials(credentials)){
//            credentials = promptUserForConnection();
//        }
//
//        if (DEBUGGING){
//            System.out.println(credentials.getHostName());
//            System.out.println(credentials.getUsername());
//            System.out.println(credentials.getPassword());
//        }
//
//        configureEnvironment(credentials);

        configureTestEnvironment();

        initializeDirAndLinkIcons();

        updateUrlBarAndDirectories();
    }

    private void onFileInfoOpened (FileInfo entry){
        if (FileInfo.isDirectoryOrLink(entry.getSftpATTRS())) {
            fileSender.cd(entry.getFileName());
            updateUrlBarAndDirectories();
        } else {
            if (DEBUGGING) {
                System.out.println(String.format("Opening file: %s", entry.getFileName()));
            }
            try {
                File localSaveLocation = downloadFileLocally(entry.getFileName(), false);
                Desktop.getDesktop().open(localSaveLocation);
            } catch (SftpException e) {
                if (DEBUGGING) {
                    e.printStackTrace();
                }
                throw new RuntimeException("Failed to copy file locally...");
                //todo: error message when failing to transfer file
            } catch (IOException e) {
                if (DEBUGGING) {
                    e.printStackTrace();
                    System.out.println("Failed to open file");
                }
                throw new RuntimeException("Failed to open file after copying locally...");
                //todo: error message when failing to open file
            }
        }
    }

    private void initializeDirAndLinkIcons() throws IOException {
        //initialize the folder icon
//        IconFetcher.addFileIcon(".", ".");
        IconFetcher.addFileIcon("testShortcut", "lnk");

        BufferedImage shortcutBufferedImage = ImageIO.read(getClass().getClassLoader().getResource("shortcuticon.png"));
        BufferedImage scaledShortcutBufferedImage = Scalr.resize(shortcutBufferedImage, iconSize);
        Image shortcutIcon = SwingFXUtils.toFXImage(scaledShortcutBufferedImage, null);
        IconFetcher.addFileIcon(":link_icon", shortcutIcon);
    }

    private void uploadFileRecursively(Path localPath, String remotePathRoot) throws IOException {

        final String remotePathSubFolder = remotePathRoot.concat("/").concat(localPath.getFileName().toString());

        if (DEBUGGING){
            System.out.println(String.format("Local Path: %s", localPath));
            System.out.println(String.format("Remote Path Root: %s", remotePathRoot));
            System.out.println(String.format("Remote Path Subfolder: %s", remotePathSubFolder));
        }

        Files.walkFileTree(localPath, new SimpleFileVisitor<>(){
            @Override
            public FileVisitResult visitFile(Path dir, BasicFileAttributes attrs){
                //only interested in files, not directories
                Path relativeFilePath = localPath.relativize(dir);
                String remoteFinalAbsolutePath = remotePathSubFolder;
                if (!relativeFilePath.toString().equals("")){
                    remoteFinalAbsolutePath = remoteFinalAbsolutePath.concat("/").concat(relativeFilePath.toString());
                }
                remoteFinalAbsolutePath = remoteFinalAbsolutePath.replace("\\", "/");

                fileSender.sendFile(dir.toString(), remoteFinalAbsolutePath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {

                //create directories
                Path relativeFilePath = localPath.relativize(dir);
                String remoteFinalAbsolutePath = remotePathSubFolder;
                if (!relativeFilePath.toString().equals("")){
                    remoteFinalAbsolutePath = remoteFinalAbsolutePath.concat("/").concat(relativeFilePath.toString());
                }
                remoteFinalAbsolutePath = remoteFinalAbsolutePath.replace("\\", "/");

                try {
                    fileSender.stat(remoteFinalAbsolutePath);
                } catch (SftpException e) {
                    if (DEBUGGING){
                        System.out.println("Directory doesn't exist...");
                    }
                    try {
                        fileSender.mkdir(remoteFinalAbsolutePath);
                    } catch (SftpException sftpException) {
                        sftpException.printStackTrace();
                    }
                }
                return FileVisitResult.CONTINUE;
            }

        });
    }

    private File downloadFileLocally(String relativeFileName, boolean downloadFolder) throws SftpException {
        String localSaveFolder = saveLoc + fileSender.getRemotePath();
        File localSaveFolderFile = new File(localSaveFolder);
        if (!localSaveFolderFile.exists()){
            if (localSaveFolderFile.mkdirs()){
                if (DEBUGGING){
                    System.out.println(String.format("Successfully created folder: %s", localSaveFolder));
                }
            }
            else{
                throw new RuntimeException("Failed to create folder to handle remote file");
                //todo: notify user
            }
        }

        String remoteSaveLocation = fileSender.getRemotePath().concat("/").concat(relativeFileName);
        String localSaveLocation = saveLoc.concat(remoteSaveLocation);
        if (DEBUGGING){
            System.out.println(String.format("Remote Save Location %s", remoteSaveLocation));
            System.out.println(String.format("Local Save Location %s", localSaveLocation));
        }

        File rootFileLocation;

        if (downloadFolder){
            if (DEBUGGING){
                System.out.println("Downloading Recursively...");
            }

            rootFileLocation = new File(localSaveLocation);
            rootFileLocation.mkdir();

            downloadFileRecursively(localSaveLocation, remoteSaveLocation);

        }
        else{
            rootFileLocation = fileSender.getFile(relativeFileName, localSaveLocation);
        }

        return rootFileLocation;

    }

    private void downloadFileRecursively(String localSourceLocation, String remoteSourceLocation) throws SftpException{
        System.out.println(String.format("Getting files for: %s", remoteSourceLocation));
        List<ChannelSftp.LsEntry> dirList = fileSender.ls(remoteSourceLocation);

        for (ChannelSftp.LsEntry entry : dirList){

            String localFileLocation = localSourceLocation.concat("/").concat(entry.getFilename());
            String remoteFileLocation = remoteSourceLocation.concat("/").concat(entry.getFilename());
            //link will not be downloaded

            if (!entry.getAttrs().isDir()){
                fileSender.getFile(remoteFileLocation, localFileLocation);
            }
            else if (!FileSender.isDotOrDotDotDirectory(entry.getFilename())){
                new File(localFileLocation).mkdir();
                downloadFileRecursively(localFileLocation, remoteFileLocation);
            }

        }

    }

    private void updateUrlBarAndDirectories(){

        currentUrl.setText(fileSender.getRemotePath());

        try{
            folderItems.getItems().clear();

            ArrayList<FileInfo> directoryFiles = new ArrayList<>();

            for (ChannelSftp.LsEntry entry : fileSender.listItems()) {
                directoryFiles.add(new FileInfo(entry.getFilename(), entry.getAttrs()));
            }

            Collections.sort(directoryFiles);

            folderItems.getItems().addAll(directoryFiles);
        }
        catch(SftpException  e){
            if (DEBUGGING){
                System.out.println("Failed to set directory and path....");
                e.printStackTrace();
            }
            //todo: show warning dialog
        }
    }

    private void setRemoteDirectoryToUrlBar(){
        fileSender.cd(currentUrl.getText());
    }

    private SSHSessionCredentials promptUserForConnection(){
        Dialog<SSHSessionCredentials> hostAndUserNameDialog = new Dialog<>();
        hostAndUserNameDialog.setTitle("Host and Username");
        hostAndUserNameDialog.setHeaderText("Please enter your hostname, username, and password");

        ButtonType connectButtonType = new ButtonType("Connect", ButtonBar.ButtonData.OK_DONE);
        hostAndUserNameDialog.getDialogPane().getButtonTypes().addAll(connectButtonType, ButtonType.CANCEL);

        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(20, 150, 10, 10));

        TextField hostnameField = new TextField();
        hostnameField.setPromptText("hostname");
        TextField usernameField = new TextField();
        usernameField.setPromptText("username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("password");

        gridPane.add(new Label("Hostname:"), 0, 0);
        gridPane.add(hostnameField, 1, 0);
        gridPane.add(new Label("Username:"), 0, 1);
        gridPane.add(usernameField, 1, 1);
        gridPane.add(new Label("Password:"), 0, 2);
        gridPane.add(passwordField, 1, 2);


        hostAndUserNameDialog.getDialogPane().setContent(gridPane);

        Platform.runLater(() -> hostnameField.requestFocus());

        hostAndUserNameDialog.setResultConverter(dialogButton -> {
            if (dialogButton == connectButtonType){
                return new SSHSessionCredentials(hostnameField.getText(), usernameField.getText(), passwordField.getText());
            }
            return null;
        });

        Optional<SSHSessionCredentials> sshSessionCredentialsOptional = hostAndUserNameDialog.showAndWait();

        if (sshSessionCredentialsOptional.isPresent()){
            return sshSessionCredentialsOptional.get();
        }
        else{
            System.out.flush();
            System.exit(0);
        }

        return null;

    }

    private boolean isInvalidCredentials(SSHSessionCredentials credentials) {
        return credentials == null ||
                isEmptyOrNullString(credentials.getHostName()) ||
                isEmptyOrNullString(credentials.getUsername()) ||
                isEmptyOrNullString(credentials.getPassword());
    }


    private boolean isEmptyOrNullString(String s){
        return s == null || s.length() == 0;
    }

    public void sendFileRemoteServer(String localFileSrc, String remoteFileDest){

        if (fileSender == null){
            if (DEBUGGING){
                System.out.println("Trying to send when Jsch is not configured");
            }
            //todo: not configured yet.
            return;
        }

        fileSender.sendFile(localFileSrc, remoteFileDest);
    }

    public void configureEnvironment(SSHSessionCredentials credentials){
        configureJschClient(credentials);
        configureFileManager();
    }

    private void configureJschClient(SSHSessionCredentials credentials){
        String knownHosts = System.getenv("USERPROFILE").replace("\\", "/");
        knownHosts = knownHosts.concat("/.ssh/known_hosts");

        if (App.DEBUGGING){
            System.out.printf("Known Host: %s%n", knownHosts);

            File knownHostsFile = new File(knownHosts);

            if (knownHostsFile.exists()){
                System.out.println("Known Hosts Exists");
            }

        }

        try{
            JschSessionClient jschSession = new JschSessionClient(knownHosts, credentials.getHostName(), credentials.getUsername(), credentials.getPassword());
            this.fileSender = new FileSender(jschSession.getJschSession());
            this.remoteTerminal = new RemoteTerminal(jschSession.getJschSession(), new TextAreaOutputStream(terminalOutput));
        }
        catch (JSchException e){
            if (DEBUGGING){
                e.printStackTrace();
                System.err.println("JSchException has been thrown...");
            }

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Error Configuring JSch Client");
            alert.setHeaderText("Error Configuring JSch Client");
            alert.setContentText("Would you like to retry?");
            ButtonType yesButton = new ButtonType("Yes", ButtonBar.ButtonData.YES);
            ButtonType noButton = new ButtonType("No", ButtonBar.ButtonData.NO);
            ButtonType cancelButton = new ButtonType("Yes", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(yesButton, noButton, cancelButton);

            alert.showAndWait().ifPresent(type -> {
                if (DEBUGGING){
                    System.out.println(String.format("Button Type Pressed: %s", type.toString()));
                }

                if (type == ButtonType.YES) {

                }
                else if (type == ButtonType.NO) {

                }
                else if (type == ButtonType.CANCEL){

                }
            });

            //todo: failed to configure....
        }

        if (App.DEBUGGING){
            System.out.println("Done Configuring Jsch!");
        }

    }

    private void configureFileManager(){
        String format = "yyyy-MM-dd hh mm ss";
        String currentTime = new SimpleDateFormat(format).format(new Date());
        saveLoc = System.getenv("APPDATA").replace("\\", "/");
        saveLoc = saveLoc.concat("/FileTransfer").concat("/").concat(currentTime);
        File saveLocFile = new File(saveLoc);

        if (!saveLocFile.exists()){
            if (saveLocFile.mkdirs()){
                if (DEBUGGING){
                    System.out.println("Created appdata successfully!");
                }
            }
            else{
                if (DEBUGGING){
                    System.out.println("Failed to create appdata...");
                }
                //todo: failed creating appdata...
            }
        }

        try{
            fileMonitor = new FileMonitor(saveLocFile, fileSender);
            fileMonitor.startFileMonitor();
        }
        catch(Exception e){
            //todo: failed to start file monitor
            throw new RuntimeException("Failed to start monitor...");
        }
    }


    public void configureTestEnvironment(){

        Properties loginProperties = new Properties();

        try (FileReader in = new FileReader("login.properties")) {
            loginProperties.load(in);
        }
        catch(Exception e){
            if (App.DEBUGGING){
                System.out.println("Failed to load login properties...");
            }
            return;
        }

        String hostname = "linux.student.cs.uwaterloo.ca";
        String username = loginProperties.getProperty("username");
        String password = loginProperties.getProperty("password");

        configureEnvironment(new SSHSessionCredentials(hostname, username, password));
    }




}
