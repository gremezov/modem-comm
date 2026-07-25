# modem-comm
**Warning**: This software is still in a very early stage of development. The current version provides a few limited features and a bare-bones UI.
Some features may not be present or not be working correctly.  

modem-comm is a program for communicating with serial modems that was developed to be able to run under many different operating systems. It includes both a graphical user interface
as well as a command-line interface meaning that it can be used both in a graphical and a text-based setting. Additionally it can also be easilly integrated with scripts. The current
version was sucessfully tested under Linux and Windows.  

Some currently supported features include USSD messaging, scanning serial ports to detect modems, and launching terminals for communicating with arbitraty serial ports.

## building

The build-process is managed using Apache Maven. To build it on your system,

1. Install Java and Apache Maven
2. Download `pom.xml` and the `src/` directory from this repository
3. `cd` into the directory containing `pom.xml` and `src/`
4. run `mvn clean package`

The resulting `.jar` file can be found in the `target/` directory as `modem-comm-VERSION.jar` and can be run using `java -jar modem-comm-VERSION.jar`
