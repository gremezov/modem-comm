/* 	Notes
		- Implement GUI serial terminal as a separate window
			- choose port and baud rate in main window, launch terminal in separate window
*/

package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import org.apache.commons.cli.*;
import javax.swing.*;
import javax.swing.JOptionPane;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.Scanner;

public class Main {

	private static boolean is_gui = false;
	private static boolean verbose_output = false;

	private static SerialPort modemport = null;	// set to null temporary before initializing it below

	// specifies the baud rate used for all operations with serial ports, except for the GUI serial terminal where this value may be over-ridden
	private static int baudrate = 115200;

	public static void main(String[] args) {

		CommandLineParser parser = new DefaultParser();
		Options options = new Options();

		options.addOption(Option.builder("u").longOpt("ussd").argName("code").hasArg().desc("send a USSD code").build());
		options.addOption(Option.builder("p").longOpt("port").argName("port-name").hasArg().desc("manually select the modem's serial port").build());
		options.addOption(Option.builder("b").longOpt("baud").argName("baud-rate").hasArg().desc("specify the baud rate").build());
		options.addOption(Option.builder("m").longOpt("sms").argName("phone-number message").numberOfArgs(2).desc("send an SMS").build());
		options.addOption("a", "auto-select-port", false, "select a modem port by scanning all available serial ports");
		options.addOption("s", "scan", false, "scan all available serial ports and print results");
		options.addOption("h", "help", false, "show a help message");
		options.addOption("v", "verbose", false, "print verbose diagnostic messages");
		options.addOption("g", "gui", false, "launch the GUI");
		options.addOption("t", "terminal", false, "launch a serial terminal");

		try{
			CommandLine cmdline = parser.parse(options, args);

			if(cmdline.hasOption("gui") || System.console() == null){
				is_gui = true;
				SwingUtilities.invokeLater(() -> {
					runGUI();
				});
			} else if(System.console() != null && args.length == 0){
				logError("Error: No arguments give, try -h for help.\n");
				System.exit(1);
			}

			if(cmdline.hasOption("help")){
				HelpFormatter formatter = new HelpFormatter();
				formatter.printHelp("modemcomm", options);
				System.exit(0);
			}
			if(cmdline.hasOption("scan")){
				verbose_output = true;
				scanForModemPorts();
				System.exit(0);
			}
			if(cmdline.hasOption("verbose")){
				verbose_output = true;
			}
			if(cmdline.hasOption("baud")){
				try{
					baudrate = Integer.parseInt(cmdline.getOptionValue("baud"));
				} catch (NumberFormatException e){
					logError("Error: Baud rate must be an integer.\n");
					System.exit(1);
				}
			}

			if(cmdline.hasOption("auto-select-port")){
				if(is_gui){
					JOptionPane.showMessageDialog(null, "Press \"Ok\" to scan serial ports for a modem", "run scan", JOptionPane.PLAIN_MESSAGE);
				} else{
					System.out.println("Scanning serial ports for a modem...");
				}
				SerialPort[] modemPorts = scanForModemPorts();
				if(modemPorts == null){
					logError("Error: No serial ports found.\n");
					System.exit(1);
				} else if(modemPorts.length == 0){
					logError("Error: No modems found.\n");
					System.exit(1);
				}
				modemport = modemPorts[0];
				if(is_gui){
					JOptionPane.showMessageDialog(null, "Selecting port "+modemport.getSystemPortName(), "modem selected", JOptionPane.PLAIN_MESSAGE);
				} else{
					System.out.println("Selecting port "+modemport.getSystemPortName()+"\n");
				}
			} else if(cmdline.hasOption("port")){
				String manual_port_name = cmdline.getOptionValue("port");

				// getCommPort only takes the port name not the full path so on linux, for example, ttyUSB0 must be passed instead of /dev/ttyUSB0
				if(manual_port_name.substring(0,5).equals("/dev/")){
					manual_port_name = manual_port_name.substring(5);
				}

				// check if specified port exists
				SerialPort[] allPorts = SerialPort.getCommPorts();
				for(SerialPort p : allPorts){
					if(p.getSystemPortName().equals(manual_port_name)){
						modemport = SerialPort.getCommPort(manual_port_name);
						break;
					}
				}
				if(modemport == null){
					logError("Error: Port "+manual_port_name+" does not exist.\n");
					System.exit(1);
				}
			}

			if(cmdline.hasOption("terminal") && !is_gui){
				if(modemport == null){
					logError("Error: No port selected.\n");
					System.exit(1);
				} else {
					if(startSerialTerminal(modemport, baudrate, "\r\n") == -1){
						System.exit(1);
					} else{
						System.exit(0);
					}
				}
			}
			if(cmdline.hasOption("ussd")){
				if(modemport == null){
					logError("Error: No port selected.\n");
					System.exit(1);
				}
				String resp = sendReceiveUSSD(modemport, cmdline.getOptionValue("ussd"));
				if(resp == null){
					System.exit(1);
				} else {
					System.out.println(resp);
					System.exit(0);
				}
			}
		} catch (ParseException e){
			logError("Error: Failed to parse command-line arguments. Reason: "+e.getMessage()+"\n");
		}
	}

	// frame and panels
	private static JFrame frame;
	private static JPanel menuPanel;
	private static JPanel mainPanel;
	private static JPanel homePanel;
	private static JPanel USSDPanel;
	private static JPanel terminalPanel;

	// menu buttons
	private static JButton homeMenuButton;
	private static JButton USSDMenuButton;
	private static JButton terminalMenuButton;

	// Home screen
	private static JComboBox<String> homePortSelectorComboBox;

	// USSD screen
	private static JLabel USSDLabel;
	private static JTextField USSDInputField;
	private static JButton USSDSendButton;
	private static JTextArea USSDReplyArea;
	private static JScrollPane USSDReplyAreaScrollPane;

	// terminal screen
	private static JLabel terminalPortLabel;
	private static JComboBox<String> terminalPortSelectorComboBox;
	private static JLabel terminalBaudLabel;
	private static JTextField terminalBaudInputField;
	private static JLabel terminalEndcharsLabel;
	private static JTextField terminalEndcharsInputField;
	private static JButton terminalLaunchButton;

	private static void runGUI(){

		// function runGUI
		// Initializes and runs the swing GUI.

		String no_port_selected_str = "- none -";

		// get all port names for usage in port selection lists
		SerialPort[] sp = SerialPort.getCommPorts();
		String[] port_names = new String[sp.length+1];
		port_names[0] = no_port_selected_str;	// default option is none
		for(int i = 0; i < sp.length; i++){
			port_names[i+1] = sp[i].getSystemPortName();
		}

		frame = new JFrame("modem-comm");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(600,400);
		frame.setLocationRelativeTo(null);	// centers the window

		/* Home */

		homePanel = new JPanel();
		homePanel.setBackground(Color.LIGHT_GRAY);

		homeMenuButton = new JButton("Home");
		homeMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		homeMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "homePanel");
		});

		homePortSelectorComboBox = new JComboBox<>(port_names);

		homePortSelectorComboBox.addActionListener(e -> {
			String selected_port_name = (String)homePortSelectorComboBox.getSelectedItem();
			if(selected_port_name.equals(no_port_selected_str)){
				modemport = null;
			} else {
				modemport = SerialPort.getCommPort(selected_port_name);
			}
		});

		homePanel.add(homePortSelectorComboBox);

		/* USSD */

		USSDPanel = new JPanel();
		USSDPanel.setBackground(Color.LIGHT_GRAY);

		USSDMenuButton = new JButton("USSD");
		USSDMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		USSDLabel = new JLabel("Enter a USSD code:"/*, SwingConstants.CENTER*/);
		USSDLabel.setFont(new Font("Arial", Font.BOLD, 14));

		USSDInputField = new JTextField(20);
		USSDInputField.setFont(new Font("Arial", Font.PLAIN, 14));

		USSDSendButton = new JButton("Send");
		USSDSendButton.setFont(new Font("Arial", Font.BOLD, 14));

		USSDReplyArea = new JTextArea(10, 30);
		USSDReplyArea.setFont(new Font("Arial", Font.PLAIN, 14));
		USSDReplyArea.setEditable(false);
		USSDReplyArea.setLineWrap(true);
		USSDReplyArea.setWrapStyleWord(true);

		USSDReplyAreaScrollPane = new JScrollPane(USSDReplyArea);

		USSDMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "USSDPanel");
		});

		USSDSendButton.addActionListener(e -> {
			String input = USSDInputField.getText();
			if(!input.equals("")){
				startSendReceiveUSSD(modemport, input);
			}
		});

		USSDPanel.add(USSDLabel);
		USSDPanel.add(USSDInputField);
		USSDPanel.add(USSDSendButton);
		USSDPanel.add(USSDReplyAreaScrollPane);

		/* Terminal */

		terminalPanel = new JPanel();
		terminalPanel.setBackground(Color.LIGHT_GRAY);

		terminalMenuButton = new JButton("Terminal");
		terminalMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		terminalMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "terminalPanel");
		});

		terminalPortLabel = new JLabel("Select serial port:");
		terminalPortLabel.setFont(new Font("Arial", Font.BOLD, 14));

		terminalPortSelectorComboBox = new JComboBox<>(port_names);

		terminalBaudLabel = new JLabel("Enter baud rate:");
		terminalBaudLabel.setFont(new Font("Arial", Font.BOLD, 14));

		terminalBaudInputField = new JTextField(10);
		terminalBaudInputField.setFont(new Font("Arial", Font.PLAIN, 14));
		terminalBaudInputField.setText(Integer.toString(baudrate));	// set default baud rate to global baud rate

		terminalEndcharsLabel = new JLabel("Enter ending character sequence:");
		terminalEndcharsLabel.setFont(new Font("Arial", Font.BOLD, 14));

		terminalEndcharsInputField = new JTextField("\\r\\n", 10);
		terminalEndcharsInputField.setFont(new Font("Arial", Font.PLAIN, 14));

		terminalLaunchButton = new JButton("Launch serial terminal");
		terminalLaunchButton.setFont(new Font("Arial", Font.BOLD, 14));

		terminalLaunchButton.addActionListener(e -> {
			String selected_port_name = (String)terminalPortSelectorComboBox.getSelectedItem();
			if(selected_port_name.equals(no_port_selected_str)){
				logError("Error: Please select a serial port.");
				return;
			}
			String st_baudrate_str = terminalBaudInputField.getText();
			if(st_baudrate_str.equals("")){
				logError("Error: Please input the baud rate.");
				return;
			}
			int st_baudrate = 0;	// baud rate to use for the serial terminal, could be different from the global baud rate
			try{
				st_baudrate = Integer.parseInt(st_baudrate_str);
			} catch (NumberFormatException exc){
				logError("Error: Baud rate must be an integer.");
				return;
			}
			startSerialTerminal(SerialPort.getCommPort(selected_port_name), st_baudrate, terminalEndcharsInputField.getText().replace("\\n", "\n").replace("\\r", "\r"));
		});

		terminalPanel.add(terminalPortLabel);
		terminalPanel.add(terminalPortSelectorComboBox);
		terminalPanel.add(terminalBaudLabel);
		terminalPanel.add(terminalBaudInputField);
		terminalPanel.add(terminalEndcharsLabel);
		terminalPanel.add(terminalEndcharsInputField);
		terminalPanel.add(terminalLaunchButton);

		/* menu */

		menuPanel = new JPanel();
		menuPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		menuPanel.add(homeMenuButton);
		menuPanel.add(USSDMenuButton);
		menuPanel.add(terminalMenuButton);

		/* main panel */

		mainPanel = new JPanel();
		mainPanel.setLayout(new CardLayout());

		mainPanel.add(homePanel, "homePanel");
		mainPanel.add(USSDPanel, "USSDPanel");
		mainPanel.add(terminalPanel, "terminalPanel");

		/* Frame final config */

		frame.setLayout(new BorderLayout());
		frame.add(mainPanel, BorderLayout.CENTER);
		frame.add(menuPanel, BorderLayout.NORTH);

		frame.setVisible(true);
	}

	private static void startSendReceiveUSSD(SerialPort modemport, String msg){

		// function startSendReceiveUSSD
		// Uses SwingWorker to run sendReceiveUSSD in the background and update the UI once the reply is received.

		SwingWorker sw1 = new SwingWorker<String, String>(){
			@Override
			protected String doInBackground(){
				if(modemport == null){
					logError("Error: No port has been selected or found yet.\n");
					return null;
				} else{
					return sendReceiveUSSD(modemport, msg);
				}
			}

			@Override
			protected void done(){
				try{
					String reply = get();
					if(reply == null){
						USSDReplyArea.setText("");
					} else{
						USSDReplyArea.setText(reply);
					}
					mainPanel.revalidate();
					mainPanel.repaint();
				} catch (InterruptedException e){e.printStackTrace();} catch (ExecutionException e){e.printStackTrace();}
			}
		};
		sw1.execute();
	}

	/*private static void startSerialTerminal(SerialPort port, int baudrate, String end_chars){
		SwingWorker sw1 = new SwingWorker<Void, Void>(){
			@Override
			protected Void doInBackground(){
				serialTerminal(port, baudrate, end_chars);
				return null;
			}
		};
		sw1.execute();
	}*/

	private static int startSerialTerminal(SerialPort port, int baudrate, String end_chars){

		// function startSerialTerminal
		// Launches a serial terminal, either in the system's terminal or using a GUI.
		// GUI mode: returns -1 if error and 0 if everything ok.
		// Terminal mode: returns -1 if error and never returns (runs in a loop) if everything ok.

		if(!port.openPort()){
			logError("Error: Failed to open port "+port.getSystemPortName()+"\n");
			return -1;
		}

		configPort(port, baudrate);
		PrintStream out = new PrintStream(port.getOutputStream());

		if(is_gui){

			// GUI serial terminal

			JFrame stFrame = new JFrame("Serial Terminal - "+port.getSystemPortName());
			stFrame.setSize(500, 300);
			stFrame.setLocationRelativeTo(null);

			JTextArea stArea = new JTextArea(10, 30);
			stArea.setFont(new Font("Arial", Font.PLAIN, 14));
			stArea.setEditable(false);
			stArea.setLineWrap(true);
			stArea.setWrapStyleWord(true);

			/*JButton stClosePortButton = new JButton("Close port");
			stClosePortButton.setFont(new Font("Arial", Font.BOLD, 14));*/

			JScrollPane stAreaScrollPane = new JScrollPane(stArea);

			JTextField stInputField = new JTextField(25);
			stInputField.setFont(new Font("Arial", Font.PLAIN, 14));

			JButton stSendButton = new JButton("Send");
			stSendButton.setFont(new Font("Arial", Font.BOLD, 14));

			stSendButton.addActionListener(e -> {
				String cmd = stInputField.getText()+end_chars;
				out.print(cmd);
				out.flush();

				stArea.append(cmd);
			});

			stFrame.setLayout(new FlowLayout(FlowLayout.LEFT));
			stFrame.add(stAreaScrollPane);
			stFrame.add(stInputField);
			stFrame.add(stSendButton);

			stFrame.setVisible(true);

			SwingWorker sw1 = new SwingWorker<Void, Void>(){
				@Override
				protected Void doInBackground(){

					while(!isCancelled()){
						if(port.bytesAvailable() > 0){
							stArea.append(ModemUtil.serialRead(port));
						}
					}
					/*port.addDataListener(new SerialPortDataListener(){
						@Override
						public int getListeningEvents(){
							return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
						}
						@Override
						public void serialEvent(SerialPortEvent event){
							if(event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE){
								System.out.println(ModemUtil.serialRead(port));
							}
						}
					});

					while(!isCancelled()){
						try{Thread.sleep(1000);}catch(InterruptedException exc){}
					}

					port.removeDataListener();*/

					out.close();
					port.closePort();

					return null;
				}
			};

			stFrame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent e){
					sw1.cancel(true);
				}
			});

			sw1.execute();
		} else{

			// in-terminal serial terminal

			Scanner scanner = new Scanner(System.in);

			System.out.printf("Connection opened to "+port.getSystemPortName()+", baud rate = %d\n", baudrate);

			while(true){
				try{	// need to use try-catch because System.in.available() could throw IO exception
					if(System.in.available() > 0){
						out.print(scanner.nextLine()+end_chars);
						out.flush();
					}
				} catch (IOException e){
					e.printStackTrace();
				}
				if(port.bytesAvailable() > 0){
					System.out.print(ModemUtil.serialRead(port));
				}
			}
			// the loop above can only be exited by ^C'ing the program so closing the stuff below don't matter
			//scanner.close();
			//out.close();
			//port.closePort();
		}
		return 0;
	}

	private static void configPort(SerialPort port, int baudrate){

		// function configPort
		// Sets the baud rate and other settings of a serial port.

		port.setBaudRate(baudrate);
		port.setNumDataBits(8);
		port.setNumStopBits(SerialPort.ONE_STOP_BIT);
		port.setParity(SerialPort.NO_PARITY);
	}

	private static void logVerboseOutput(String msg, Object... extra_args){

		// function logVerboseOutput
		// Prints output only if verbose_output is true and if we are not in the GUI.

		if(verbose_output && !is_gui){
			System.out.printf(msg, extra_args);
		}
	}

	private static void logError(String err_msg, Object... extra_args){

		// function logError
		// Prints an error if in terminal and displays error popup if in GUI.

		if(is_gui){
			JOptionPane.showMessageDialog(null, String.format(err_msg, extra_args), "Error", JOptionPane.ERROR_MESSAGE);
		} else {
			System.err.printf(err_msg, extra_args);
		}
	}

	private static String sendReceiveUSSD(SerialPort modemport, String ussd_code) {

		// function sendReceiveUSSD
		// Sends a USSD code and then returns the response. Returns null if encounters an error.

		if(!modemport.openPort()){
			logError("Error: Failed to open port "+modemport.getSystemPortName()+"\n");
			return null;
		}

		configPort(modemport, baudrate);

		PrintStream out = new PrintStream(modemport.getOutputStream());

		// enable numeric error report
		out.print("AT+CMEE=1\r\n");
		out.flush();

		int resp = ModemUtil.checkResponseOK(modemport);
		if(resp == -2){
			logError("Error: Modem timeout.\n");
			out.close();
			modemport.closePort();
			return null;
		} else if(resp == -1){
			logError("Error: Modem reported an error.\n");
			out.close();
			modemport.closePort();
			return null;
		}

		// set text encoding to "GSM"
		out.print("AT+CSCS=\"GSM\"\r\n");
		out.flush();

		resp = ModemUtil.checkResponseOK(modemport);
		if(resp == -2){
			logError("Error: Modem timeout.\n");
			out.close();
			modemport.closePort();
			return null;
		} else if(resp == -1){
			logError("Error: Modem reported an error.\n");
			out.close();
			modemport.closePort();
			return null;
		}

		// send USSD code
		out.print("AT+CUSD=1,\""+ussd_code+"\",15\r\n");
		out.flush();

		resp = ModemUtil.checkResponseOK(modemport);
		if(resp == -2){
			logError("Error: Modem timeout.\n");
			out.close();
			modemport.closePort();
			return null;
		} else if(resp == -1){
			logError("Error: Modem reported an error.\n");
			out.close();
			modemport.closePort();
			return null;
		}

		int ussd_timeout = 10000; // in milliseconds
		long msg_sent_time = System.nanoTime();

		String reply = null;	// default value to return if error occurs

		// USSD reply waiting loop
		replyScanLoop:
		while(true){
			if(modemport.bytesAvailable() > 0){
				String[] input_lines = ModemUtil.serialReadLines(modemport);
				if(input_lines.length > 0){
					for(String s : input_lines){
						if(s.length() >= 7 && s.substring(0,7).equals("+CUSD: ")){
							String[] reply_items = s.substring(7).replace("\"", "").split(",");
							// XXX TODO XXX --- Implement checking whether reply from user is needed
							int reply_encoding = Integer.parseInt(reply_items[2]);
							if(reply_encoding == 15){
								reply = ModemUtil.decode7bitGSM(HexFormat.of().parseHex(reply_items[1]));
							} else if (reply_encoding == 72){
								reply = new String(HexFormat.of().parseHex(reply_items[1]), StandardCharsets.UTF_16BE);
							}else{
								logError("Error: USSD response uses an unrecognized encoding format: %d\n", reply_encoding);
							}
							break replyScanLoop;
						} else if(s.length() >= 12 && s.substring(0,12).equals("+CME ERROR: ")){
							logError("Error: Modem reported an error: "+s+"\n");
							break replyScanLoop;
						}
					}
				}
			}
			if((System.nanoTime()-msg_sent_time)/1000000 > ussd_timeout){
				logError("Error: USSD timeout.\n");
				break replyScanLoop;
			}
		}
		out.close();
		modemport.closePort();
		return reply;
	}

	private static SerialPort[] scanForModemPorts(){

		// function scanForModemPorts
		// Scans for modems on all serial ports, prints progress messages, and returns an array of modem ports.
		// Returns an empty array if no modems were found. Returns null if no serial ports were found at all.

		SerialPort[] availablePorts = SerialPort.getCommPorts();

		if(availablePorts.length == 0){
			logVerboseOutput("No serial ports found.\n\n");
			return null;
		}

		int modem_ports_cnt = 0;
		int[] modem_ports_indexes = new int[availablePorts.length];

		for(int i = 0; i < availablePorts.length; i++){
			SerialPort port = availablePorts[i];

			logVerboseOutput("Found serial port " + port.getSystemPortName()+"\n");
			logVerboseOutput("  description: " +port.getDescriptivePortName()+"\n");
			logVerboseOutput("  opening...");

			if(!port.openPort()){
				logVerboseOutput("failed\n");
				continue;
			}
			logVerboseOutput("success\n");
			logVerboseOutput("  sending ATQ0 command...");

			configPort(port, baudrate);

			PrintStream out = new PrintStream(port.getOutputStream());
			out.print("ATQ0\r\n");
			out.close();	// also helps flush the buffer

			long cmd_sent_time = System.nanoTime();
			int timeout = 2000; // in milliseconds
			boolean received_data = false;

			// loop for detecting "OK"
			responseScanLoop:
			while(true){
				if(port.bytesAvailable() > 0){
					received_data = true;
					String[] input_lines = ModemUtil.serialReadLines(port);
					if(input_lines.length > 0){
						for(String s : input_lines){
							if(s.length() >= 2 && s.substring(0,2).equals("OK")){
								logVerboseOutput("received OK\n");
								modem_ports_indexes[modem_ports_cnt++] = i;
								break responseScanLoop;
							}
						}
					}
				}
				if((System.nanoTime()-cmd_sent_time)/1000000 > timeout){
					if(received_data){
						logVerboseOutput("timed out before receiving OK (but received other data)\n");
					}else{
						logVerboseOutput("timed out before receiving any data\n");
					}
					break;
				}
			}
			port.closePort();
		}

		SerialPort[] modemPorts = new SerialPort[modem_ports_cnt];

		if(modem_ports_cnt > 0){
			logVerboseOutput("\nUseable modems found on the following ports:\n");
			for(int i = 0; i < modem_ports_cnt; i++){
				logVerboseOutput("  "+availablePorts[modem_ports_indexes[i]].getSystemPortName()+"\n");
				modemPorts[i] = availablePorts[modem_ports_indexes[i]];
			}
		}else{
			logVerboseOutput("\nNo useable modems found.\n");
		}
		logVerboseOutput("\n");

		return modemPorts;
	}
}
