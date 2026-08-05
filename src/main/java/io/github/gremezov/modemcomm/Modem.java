package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.HashMap;

public class Modem{

	public record Response<T>(int status, T response, String errorMessage){}

	// status codes
	// note: all error codes are less than 0
	public static int USER_REPLY_REQUIRED = 1;
	public static int STATUS_OK = 0;
	public static int ERROR_MODEM_TIMEOUT = -1;
	public static int ERROR_MODEM_ERROR = -2;
	public static int ERROR_USSD_UNKNOWN_ENCODING = -3;

	public SerialPort modemport;

	public Modem(SerialPort modemport){
		this.modemport = modemport;
	}

	public static String decode7BitGSM(byte[] gsm_rawdata){

		// function decode7bitGSM
		// Returns decoded 7-bit GSM text as a java string.

		// TODO: implement special character mapping

		int buffer = 0;	// must be 32-bit for the below code to work correctly
		int buf_len = 31;	// make sure MSB is never used or else java might turn the number negative and corrupt the data in the buffer
		int buf_free_bits = buf_len;
		byte[] text8bit = new byte[(gsm_rawdata.length*8)/7];

		// Conversion is done in the loop below using a buffer that takes 8 bits of gsm text from
		// one side and outputs 7 bits of ascii text on the other side.

		int g = 0;
		int t = 0;
		while(g < gsm_rawdata.length){
			while(buf_free_bits >= 8 && g < gsm_rawdata.length){
				buffer |= ((gsm_rawdata[g++]&0xFF) << (buf_len-buf_free_bits));	// 0xFF used to prevent data corruption due to negative number representation in java
				buf_free_bits -= 8;
			}
			while(buf_len-buf_free_bits >= 7){
				text8bit[t++] = (byte)(buffer & 0x7F);
				buffer = buffer >> 7;
				buf_free_bits += 7;
			}
		}

		return new String(text8bit, StandardCharsets.UTF_8);
	}

	public static byte[] encode7BitGSM(String ascii){

		// function encode7bitGSM
		// Returns the 7-bit encoded text as an array of bytes.

		int buffer = 0;	// must be 32-bit for the below code to work correctly
		int buf_len = 31;	// make sure MSB is never used or else java might turn the number negative and corrupt the data in the buffer
		int buf_free_bits = buf_len;
		byte[] gsm = new byte[(ascii.length()*7)/8 + (((ascii.length()*7)%8 > 0) ? 1 : 0)];

		// Conversion is done in the loop below using a buffer that takes 7 bits of ascii text from
		// one side and outputs 8 bits of 7-bit coded data on the other side.

		int a = 0;
		int g = 0;
		while(a < ascii.length()){
			while(buf_free_bits >= 7 && a < ascii.length()){
				buffer |= ((((byte)ascii.charAt(a++))&0x7F) << (buf_len-buf_free_bits));
				buf_free_bits -= 7;
			}
			while(buf_len-buf_free_bits >= 8){
				gsm[g++] = (byte)(buffer&0xFF);
				buffer = buffer >> 8;
				buf_free_bits += 8;
			}
		}
		if(buf_len-buf_free_bits > 0){
			gsm[g] = (byte)(buffer&0xFF);
		}

		return gsm;
	}

	public static String encodePDU(String phone_number, boolean international_format, String msg){

		// function encodePDU
		// Encodes the SMS message in PDU format. Returns a hex string.
		// For more info, see [1] and [2].
		//
		// [1]: http://www.gsm-modem.de/sms-pdu-mode.html
		// [2]: https://www.smsdeliverer.com/online-sms-pdu-encoder.aspx

		// encode phone number in BCD
		char[] pnum_semioct_chars = new char[phone_number.length() + (phone_number.length()%2)];
		for(int i = 0; i < pnum_semioct_chars.length; i+=2){
			pnum_semioct_chars[i] = (i == phone_number.length()-1) ? 'F' : phone_number.charAt(i+1);
			pnum_semioct_chars[i+1] = phone_number.charAt(i);
		}
		String pnum_semioct = new String(pnum_semioct_chars);

		String pdu = 	"00"+
						"11"+
						"00"+
						String.format("%02X", phone_number.length())+
						((international_format) ? "91" : "81")+
						pnum_semioct+
						"00"+
						"00"+
						"AA"+
						String.format("%02X", msg.length())+
						HexFormat.of().withUpperCase().formatHex(encode7BitGSM(msg));

		return pdu;
	}

	public Response<String> checkResponse(String expected_response, int timeout_ms){

		// function checkResponse
		// If the expected response is detected, returned record contains the line containing the expected response.
		// If an error is detected or timeout occurs, returned record contains the appropriate ERROR_MODEM_* and an error message.

		int exp_resp_len = expected_response.length();
		long cmd_sent_time = System.nanoTime();

		while(true){
			if(modemport.bytesAvailable() > 0){
				String[] input_lines = Serial.readLines(modemport);
				if(input_lines.length > 0){
					for(String s : input_lines){
						if(s.length() >= exp_resp_len && s.substring(0,exp_resp_len).equals(expected_response)){
							return new Response<>(STATUS_OK, s, null);
						} else if(s.contains("ERROR")){
							return new Response<>(ERROR_MODEM_ERROR, null, "Modem reported an error: "+s);
						}
					}
				}
			}
			if((System.nanoTime()-cmd_sent_time)/1000000 > timeout_ms){
				return new Response<>(ERROR_MODEM_TIMEOUT, null, "Modem timeout.");
			}
		}
	}

	public Response<String> checkResponse(String expected_response){

		// function checkResponse --- (for cases when timeout is not passed) ---
		// Wrapper around checkResponse that passes a default timeout if no timeout is specified.

		int modem_timeout_default_ms = 5000;	// default timeout in milliseconds
		return checkResponse(expected_response, modem_timeout_default_ms);
	}

	public Response<String> sendReceiveUSSD(String ussd_code) {

		// function sendReceiveUSSD
		// Sends a USSD code and returns the reply.

		// Ussd timeout in milliseconds (note: some modems will only actually timeout after as long as 180 seconds
		// so the program might timeout but the message would still be sent).
		int ussd_timeout_ms = 20000;

		PrintStream out = new PrintStream(modemport.getOutputStream());

		// enable numeric error report
		out.print("AT+CMEE=1\r");
		out.flush();

		Response<String> resp = checkResponse("OK");
		if(resp.status() < 0){
			out.close();
			return resp;
		}

		// set text encoding to "GSM"
		out.print("AT+CSCS=\"GSM\"\r");
		out.flush();

		resp = checkResponse("OK");
		if(resp.status() < 0){
			out.close();
			return resp;
		}

		// send the USSD code
		out.print("AT+CUSD=1,\""+ussd_code+"\",15\r");
		out.flush();

		resp = checkResponse("OK");
		if(resp.status() < 0){
			out.close();
			return resp;
		}

		resp = checkResponse("+CUSD: ", ussd_timeout_ms);
		if(resp.status() < 0){
			out.close();
			return resp;
		}

		String[] reply_items = resp.response().substring(7).replace("\"", "").split(",");
		int reply_status = Integer.parseInt(reply_items[0]);
		int reply_encoding = Integer.parseInt(reply_items[2]);
		int status = (reply_status == 1) ? USER_REPLY_REQUIRED : STATUS_OK;	// note: reply_status can be 0-5 but only 1 means user reply is required
		if(reply_encoding == 15){
			out.close();
			return new Response<>(status, decode7BitGSM(HexFormat.of().parseHex(reply_items[1])), null);
		} else if (reply_encoding == 72){
			out.close();
			return new Response<>(status, new String(HexFormat.of().parseHex(reply_items[1]), StandardCharsets.UTF_16BE), null);
		}else{
			out.close();
			return new Response<>(ERROR_USSD_UNKNOWN_ENCODING, null, String.format("USSD response uses an unrecognized encoding format: %d", reply_encoding));
		}
	}

	public Response<Integer> sendSMS(String number, String msg){

		// function sendSMS
		// Sends an SMS to a specified number and returns the message reference number.
		// Note: number can be either in international format (+...) or local (0...) but it must not
		// contain spaces or any other delimiters.

		// SMS timeout in milliseconds (note: some modems will only actually timeout after as long as 180 seconds
		// so the program might timeout but the message would still be sent).
		int sms_timeout_ms = 20000;

		String pdu = null;
		if(number.charAt(0) == '+'){
			pdu = encodePDU(number.substring(1), true, msg);
		} else {
			pdu = encodePDU(number, false, msg);
		}

		PrintStream out = new PrintStream(modemport.getOutputStream());

		// enable numeric error report
		out.print("AT+CMEE=1\r");
		out.flush();

		Response<String> resp = checkResponse("OK");
		if(resp.status() < 0){
			out.close();
			return new Response<Integer>(resp.status(), null, resp.errorMessage());
		}

		// set text encoding to GSM
		out.print("AT+CSCS=\"GSM\"\r");
		out.flush();

		resp = checkResponse("OK");
		if(resp.status() < 0){
			out.close();
			return new Response<Integer>(resp.status(), null, resp.errorMessage());
		}

		// set PDU mode
		out.print("AT+CMGF=0\r");
		out.flush();

		resp = checkResponse("OK");
		if(resp.status() < 0){
			out.close();
			return new Response<Integer>(resp.status(), null, resp.errorMessage());
		}

		// set length of PDU
		out.printf("AT+CMGS=%d\r", pdu.length()/2 - 1);	// subtract 1 from length because the first byte (SMSC) is excluded from this length
		out.flush();

		resp = checkResponse("> ");
		if(resp.status() < 0){
			out.close();
			return new Response<Integer>(resp.status(), null, resp.errorMessage());
		}

		// write the PDU + terminating character
		out.print(pdu);
		byte[] end = {0x1A};
		modemport.writeBytes(end, 1);

		resp = checkResponse("+CMGS: ", sms_timeout_ms);
		if(resp.status() < 0){
			out.close();
			return new Response<Integer>(resp.status(), null, resp.errorMessage());
		}

		out.close();
		return new Response<Integer>(STATUS_OK, Integer.parseInt(resp.response().substring(7)), null);
	}

	public HashMap<String, String> getInfo(){

		// function getInfo
		// Returns a hashmap containing information about the modem.

		HashMap<String, String> info = new HashMap<String, String>();
		PrintStream out = new PrintStream(modemport.getOutputStream());
		String response_old = "";
		int timeout_ms = 5000;
		int i = 0;

		infoRequestLoop:
		while(true){
			out.printf("ATI%d\r", i++);
			out.flush();

			long sent_time = System.nanoTime();

			responseScanLoop:
			while(true){
				if(modemport.bytesAvailable() > 0){
					String response = Serial.read(modemport);

					// if the returned info is the same as previous info, assume the modem has already given out all the available info
					if(response.equals(response_old)){
						break infoRequestLoop;
					}

					String[] lines = response.split("\\R");
					if(lines.length > 0){

						// go through each line and create hashmaps from the data that is in the form of "<Label>: <Info>"
						for(String s : lines){
							if(s.matches("^.+: .+$")){
								String[] items = s.split(": ", 2);
								info.put(items[0], items[1]);
							}
						}
						response_old = response;
						break responseScanLoop;
					}
				}
				if((System.nanoTime()-sent_time)/1000000 > timeout_ms){
					break infoRequestLoop;
				}
			}
		}
		return info;
	}
}
