package net.tfobz.tele.eggale.ftp;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

import net.tfobz.tele.eggale.ftp.state.ComState;
import net.tfobz.tele.eggale.ftp.state.Mode;
import net.tfobz.tele.eggale.ftp.state.Type;

/**
 * @author 10eggale
 * 
 */
public class ControlThread extends Thread {

    /**
     * The Control Connection to the Client the Thread is currently handling.
     */
    private Socket controlConnection;

    /**
     * The host of the Data Connection.
     */
    private String dataHost = "localhost";

    /**
     * The port of the Data Connection.
     */
    private int dataPort = Server.CPORT + 1;

    /**
     * The handler of the data connection of this client.
     */
    private DataHandler dataHandler;

    /**
     * The Protocol Handler of this thread.
     */
    private ProtocolInterpreter comHandler;

    /**
     * The current state of the communication.
     */
    private ComState state = ComState.GREETING;

    public ControlThread(Socket client) {
        this.controlConnection = client;

        try {
            comHandler = new FTPInterpreter(controlConnection.getInputStream(),
                            controlConnection.getOutputStream());
            dataHandler = new DataHandler(comHandler);
        } catch (IOException e) {
            System.err.println("Couldn't communicate with Control Socket.");
            System.err.println(e.getMessage());
        }
    }

    public void run() {
        boolean userOk = false;
        while (Thread.currentThread().isInterrupted() == false) {
            try {
                Command command = null;
                switch (state) {
                case GREETING:
                    comHandler.reply(Reply.GREETING);
                    state = ComState.AUTHENTICATION;
                    break;

                case AUTHENTICATION:
                    command = comHandler.select();
                    switch (command) {
                    case USER:
                        String username = comHandler.getArgument();

                        // TODO: Replace with actual check.
                        if (username.equals("Anonymous")
                                        || username.equals("anonymous")) {
                            userOk = true;
                            comHandler.reply(Reply.USER_OK_PASSWORD_NEEDED);
                        } else {
                            userOk = false;
                            comHandler.reply(Reply.LOGIN_INCORRECT);
                        }
                        break;
                    case PASS:
                        if (userOk == true) {
                            String password = comHandler.getArgument();

                            // TODO: Replace with actual check.
                            if (password.equals("")) {
                                state = ComState.ARBITRARY;
                                comHandler.reply(Reply.USER_LOGGED_IN);
                            } else {
                                userOk = false;
                                comHandler.reply(Reply.BAD_SEQUENCE);
                            }
                        }
                        break;
                    case QUIT: {
                        quit();
                        break;
                    }
                    case ERROR:
                    default:
                        userOk = false;
                        comHandler.reply(Reply.BAD_SEQUENCE);
                        break;
                    }
                    break;
                case ARBITRARY:
                    command = comHandler.select();
                    switch (command) {
                    case NOOP:
                        doNoop();
                        break;
                    case PORT:
                        setPort(comHandler.getArgument());
                        break;
                    case PASV:
                        setPassiveMode();
                        break;
                    case MODE:
                        setMode(comHandler.getArgument());
                        break;
                    case STRU:
                        setStructure(comHandler.getArgument());
                        break;
                    case TYPE:
                        setType(comHandler.getArgument());
                        break;
                    case CWD:
                        changeDirectory(comHandler.getArgument());
                        break;
                    case CDUP:
                        changeDirectory("..");
                        break;
                    case PWD:
                        getWorkingDirectory();
                        break;
                    case LIST:
                        listFiles(comHandler.getArgument());
                        break;
                    case STOR:
                        storeFile(comHandler.getArgument());
                        break;
                    case RETR:
                        retrieveFile(comHandler.getArgument());
                        break;
                    case QUIT:
                        quit();
                        break;
                    case ERROR:
                    default:
                        comHandler.reply(Reply.ILLEGAL_COMMAND);
                        break;
                    }
                    break;
                }
            } catch (IOException e) {
                System.err.println("[ERROR] Thread "
                                + this.getId()
                                + " has problem communication with socket. Aborted.");
                quit();
            }
        }
        System.out.println("[INFO] Thread " + this.getId() + " has halted.");
    }

    private void doNoop() {
        comHandler.reply(Reply.COMMAND_OK);
    }

    private void setPort(String argument) {
        // Split remaining string by commas.
        String[] arguments = argument.split(",");

        if (arguments.length == 6) {
            String host = arguments[0] + "." + arguments[1] + "."
                            + arguments[2] + "." + arguments[3];

            // Checks if host is a regular IPv4 address.
            if (host.matches("^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$")) {
                dataHost = host;

                try {
                    int port = (Integer.parseInt(arguments[4]) << 8)
                                    + Integer.parseInt(arguments[5]);
                    if (port > 0 && port < 65535) {
                        dataPort = port;
                        comHandler.reply(Reply.COMMAND_OK);
                    } else {
                        comHandler.reply(Reply.ILLEGAL_COMMAND);
                    }
                } catch (NumberFormatException e) {
                    comHandler.reply(Reply.SYNTAX_ERROR);
                }
            } else {
                comHandler.reply(Reply.ILLEGAL_COMMAND);
            }
        } else {
            comHandler.reply(Reply.SYNTAX_ERROR);
        }
    }

    private void setPassiveMode() {
        dataHandler.setPassiveMode(true);
    }

    private void setStructure(String argument) {
        switch (argument) {
        case "F":
            // dataHandler.setStructure(Structure.FILE);
            comHandler.reply(Reply.COMMAND_OK);
            break;
        default:
            comHandler.reply(Reply.COMMAND_WRONG_PARAM);
        }
    }

    private void setMode(String argument) {
        switch (argument) {
        case "S":
            dataHandler.setMode(Mode.STREAM);
            comHandler.reply(Reply.COMMAND_OK);
            break;
        default:
            comHandler.reply(Reply.COMMAND_WRONG_PARAM);
        }
    }

    private void setType(String argument) {
        switch (argument.charAt(0)) {
        case 'A':
            dataHandler.setType(Type.ASCII);
            comHandler.reply(Reply.COMMAND_OK);
            break;
        case 'I':
            dataHandler.setType(Type.IMAGE);
            comHandler.reply(Reply.COMMAND_OK);
            break;
        default:
            comHandler.reply(Reply.COMMAND_WRONG_PARAM);
        }
    }

    private void changeDirectory(String directory) {
        File file = new File(directory);

        if (directory.isEmpty() == false) {
            if (directory.equals("..")) {
                // If we are at "/" and the user tries to go up by calling ".."
                // or CDUP, we do nothing.
                if (dataHandler.getWorkingDirectory().equals("/") == true) {
                    file = new File(dataHandler.getWorkingDirectory());
                } else {
                    file = new File(dataHandler.getWorkingDirectory())
                                    .getParentFile();
                }
            }
            dataHandler.changeDirectory(file);
        }
    }

    private void listFiles(String folder) {
        File file = null;
        if (folder.isEmpty() == false) {
            if (folder.startsWith("/") == true) {
                // Path is absolute.
                file = new File(folder);
            } else {
                // Path is relative.
                file = new File(dataHandler.getWorkingDirectory() + "/"
                                + folder);
            }
        } else {
            // If we have no argument, we take the current working directory as
            // argument.
            file = new File(dataHandler.getWorkingDirectory());
        }

        if (file.exists()) {
            try {
                dataHandler.openConnection(dataPort, dataHost);
                dataHandler.listFiles(file);
                dataHandler.closeConnection();
            } catch (IllegalStateException e) {
                // Continue as usual.
                // CAUTION: Is this correct behavior?
            } catch (IOException e) {
                System.err.println("[ERROR] Thread "
                                + this.getId()
                                + " has encountered a problem when listing files.");
            }
        } else {
            comHandler.reply(Reply.FILE_NOT_FOUND);
        }
    }

    private void getWorkingDirectory() {
        comHandler.reply(257, dataHandler.getWorkingDirectory());
    }

    private void storeFile(String name) {
        File file = null;
        if (name.startsWith("/") == true) {
            // Path is absolute.
            file = new File(name);
        } else {
            // Path is relative.
            file = new File(dataHandler.getWorkingDirectory() + "/" + name);
        }

        try {
            dataHandler.openConnection(dataPort, dataHost);
            dataHandler.store(file);
            dataHandler.closeConnection();
        } catch (IllegalStateException e) {
            // Connection is already open. Continue as usual.
            // CAUTION: Is this correct behavior?
        } catch (IOException e) {
            System.err.println("[ERROR] Thread " + this.getId()
                            + " has encountered a problem when storing a file.");
        }
    }

    private void retrieveFile(String file) {
        File f = new File(dataHandler.getWorkingDirectory() + "/" + file);
        if (file != null && file.isEmpty() == false) {
            if (f.exists() == true && f.isDirectory() == false) {
                try {
                    dataHandler.openConnection(dataPort, dataHost);
                    dataHandler.retrieve(f);
                    dataHandler.closeConnection();
                } catch (IllegalStateException e) {
                    // Continue as usual.
                    // CAUTION: Is this correct behavior?
                } catch (IOException e) {
                    System.err.println("[ERROR] Thread "
                                    + this.getId()
                                    + " has encountered a problem when retrieving a file.");
                }
            } else {
                comHandler.reply(Reply.FILE_NOT_FOUND);
            }
        }
    }

    private void quit() {
        comHandler.reply(Reply.CLOSING);

        try {
            controlConnection.close();
        } catch (IOException e) {
            // CAUTION: Cant recover.
            System.err.println("[ERROR] Thread " + this.getId()
                            + " has encountered an error when quiting.");
        } finally {
            // Definitely stop execution and mark for cleanup.
            this.interrupt();
        }
    }
}
