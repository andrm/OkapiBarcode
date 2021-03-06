package uk.org.okapibarcode.backend;

import static java.lang.Integer.toHexString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.ComparisonFailure;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.reflections.Reflections;

import uk.org.okapibarcode.backend.Symbol.DataType;
import uk.org.okapibarcode.output.Java2DRenderer;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.aztec.AztecReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.google.zxing.oned.CodaBarReader;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.oned.Code39Reader;
import com.google.zxing.oned.Code93Reader;
import com.google.zxing.oned.EAN13Reader;
import com.google.zxing.oned.EAN8Reader;
import com.google.zxing.oned.UPCAReader;
import com.google.zxing.oned.UPCEReader;
import com.google.zxing.pdf417.PDF417Reader;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * <p>
 * Scans the test resources for file-based bar code tests.
 *
 * <p>
 * Tests that verify successful behavior will contain the following sets of files:
 *
 * <pre>
 *   /src/test/resources/uk/org/okapibarcode/backend/[symbol-name]/[test-name].properties (bar code initialization attributes)
 *   /src/test/resources/uk/org/okapibarcode/backend/[symbol-name]/[test-name].codewords  (expected intermediate coding of the bar code)
 *   /src/test/resources/uk/org/okapibarcode/backend/[symbol-name]/[test-name].png        (expected final rendering of the bar code)
 * </pre>
 *
 * <p>
 * Tests that verify error conditions will contain the following sets of files:
 *
 * <pre>
 *   /src/test/resources/uk/org/okapibarcode/backend/[symbol-name]/[test-name].properties (bar code initialization attributes)
 *   /src/test/resources/uk/org/okapibarcode/backend/[symbol-name]/[test-name].error      (expected error message)
 * </pre>
 *
 * <p>
 * If a properties file is found with no matching expectation files, we assume that it was recently added to the test suite and
 * that we need to generate suitable expectation files for it.
 *
 * <p>
 * A single properties file can contain multiple test configurations (separated by an empty line), as long as the expected output
 * is the same for all of those tests.
 */
@RunWith(Parameterized.class)
public class SymbolTest {

    /** The directory to which barcode images (expected and actual) are saved when an image check fails. */
    private static final File TEST_FAILURE_IMAGES_DIR = new File("build", "test-failure-images");

    /** The font used to render human-readable text when drawing the symbologies; allows for consistent results across operating systems. */
    private static final Font DEJA_VU_SANS;

    static {
        String path = "/uk/org/okapibarcode/fonts/OkapiDejaVuSans.ttf";
        try {
            InputStream is = SymbolTest.class.getResourceAsStream(path);
            DEJA_VU_SANS = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            boolean registered = ge.registerFont(DEJA_VU_SANS);
            assertTrue("Unable to register test font!", registered);
        } catch (IOException | FontFormatException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** The type of symbology being tested. */
    private final Class< ? extends Symbol > symbolType;

    /** The test configuration properties. */
    private final Map< String, String > properties;

    /** The file containing the expected intermediate coding of the bar code, if this test verifies successful behavior. */
    private final File codewordsFile;

    /** The file containing the expected final rendering of the bar code, if this test verifies successful behavior. */
    private final File pngFile;

    /** The file containing the expected error message, if this test verifies a failure. */
    private final File errorFile;

    /**
     * Creates a new test.
     *
     * @param symbolType the type of symbol being tested
     * @param properties the test configuration properties
     * @param codewordsFile the file containing the expected intermediate coding of the bar code, if this test verifies successful behavior
     * @param pngFile the file containing the expected final rendering of the bar code, if this test verifies successful behavior
     * @param errorFile the file containing the expected error message, if this test verifies a failure
     * @param symbolName the name of the symbol type (used only for test naming)
     * @param fileBaseName the base name of the test file (used only for test naming)
     * @throws IOException if there is any I/O error
     */
    public SymbolTest(Class< ? extends Symbol > symbolType, Map< String, String > properties, File codewordsFile, File pngFile,
                    File errorFile, String symbolName, String fileBaseName) throws IOException {
        this.symbolType = symbolType;
        this.properties = properties;
        this.codewordsFile = codewordsFile;
        this.pngFile = pngFile;
        this.errorFile = errorFile;
    }

    /**
     * Runs the test. If there are no expectation files yet, we generate them instead of checking against them.
     *
     * @throws Exception if any error occurs during the test
     */
    @Test
    public void test() throws Exception {

        Symbol symbol = symbolType.newInstance();
        symbol.setFontName(DEJA_VU_SANS.getFontName());

        String actualError;

        try {
            setProperties(symbol, properties);
            actualError = null;
        } catch (InvocationTargetException e) {
            actualError = e.getCause().getMessage();
        }

        if (codewordsFile.exists() && pngFile.exists()) {
            verifySuccess(symbol, actualError);
        } else if (errorFile.exists()) {
            verifyError(actualError);
        } else {
            generateExpectationFiles(symbol, actualError);
        }
    }

    /**
     * Verifies that the specified symbol was encoded and rendered in a way that matches expectations.
     *
     * @param symbol the symbol to check
     * @param actualError the actual error message
     * @throws IOException if there is any I/O error
     * @throws ReaderException if ZXing has an issue decoding the barcode image
     */
    private void verifySuccess(Symbol symbol, String actualError) throws IOException, ReaderException {

        assertEquals("Error message", null, actualError);

        List< String > expectedList = Files.readAllLines(codewordsFile.toPath(), UTF_8);

        try {
            // try to verify codewords
            int[] actualCodewords = symbol.getCodewords();
            assertEquals(expectedList.size(), actualCodewords.length);
            for (int i = 0; i < actualCodewords.length; i++) {
                int expected = getInt(expectedList.get(i));
                int actual = actualCodewords[i];
                assertEquals("at codeword index " + i, expected, actual);
            }
        } catch (UnsupportedOperationException e) {
            // codewords aren't supported, try to verify patterns
            String[] actualPatterns = symbol.pattern;
            assertEquals(expectedList.size(), actualPatterns.length);
            for (int i = 0; i < actualPatterns.length; i++) {
                String expected = expectedList.get(i);
                String actual = actualPatterns[i];
                assertEquals("at pattern index " + i, expected, actual);
            }
        }

        // make sure the barcode images match
        String parentName = pngFile.getParentFile().getName();
        String pngName = pngFile.getName();
        String dirName = parentName + "-" + pngName.substring(0, pngName.lastIndexOf('.'));
        File failureDirectory = new File(TEST_FAILURE_IMAGES_DIR, dirName);
        BufferedImage expected = ImageIO.read(pngFile);
        BufferedImage actual = draw(symbol);
        assertEqual(expected, actual, failureDirectory);

        // if possible, ensure an independent third party (ZXing) can read the generated barcode and agrees on what it represents
        Reader zxingReader = findReader(symbol);
        if (zxingReader != null) {
            LuminanceSource source = new BufferedImageLuminanceSource(expected);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Map< DecodeHintType, Boolean > hints = Collections.singletonMap(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
            Result result = zxingReader.decode(bitmap, hints);
            String zxingData = massageZXingData(result.getText(), symbol);
            String okapiData = massageOkapiData(symbol.getContent(), symbol);
            assertEquals("checking against ZXing results", okapiData, zxingData);
        }
    }

    /**
     * Returns a ZXing reader that can read the specified symbol.
     *
     * @param symbol the symbol to be read
     * @return a ZXing reader that can read the specified symbol
     */
    private static Reader findReader(Symbol symbol) {

        if (symbol instanceof Code128 || symbol instanceof UspsPackage) {
            return new Code128Reader();
        } else if (symbol instanceof Code93) {
            return new Code93Reader();
        } else if (symbol instanceof Code3Of9) {
            return new Code39Reader();
        } else if (symbol instanceof Codabar) {
            return new CodaBarReader();
        } else if (symbol instanceof AztecCode) {
            return new AztecReader();
        } else if (symbol instanceof QrCode) {
            return new QRCodeReader();
        } else if (symbol instanceof DataMatrix && symbol.getEciMode() == -1) {
            // ZXing does not currently support ECI in Data Matrix symbols
            return new DataMatrixReader();
        } else if (symbol instanceof Ean) {
            Ean ean = (Ean) symbol;
            if (ean.getMode() == Ean.Mode.EAN8) {
                return new EAN8Reader();
            } else {
                return new EAN13Reader();
            }
        } else if (symbol instanceof Pdf417) {
            Pdf417 pdf417 = (Pdf417) symbol;
            if (pdf417.getMode() != Pdf417.Mode.MICRO) {
                return new PDF417Reader();
            }
        } else if (symbol instanceof Upc) {
            Upc upc = (Upc) symbol;
            if (upc.getMode() == Upc.Mode.UPCA) {
                return new UPCAReader();
            } else {
                return new UPCEReader();
            }
        }

        // no corresponding ZXing reader exists, or it behaves badly so we don't use it for testing
        return null;
    }

    /**
     * Massages ZXing barcode reading results to make them easier to check against Okapi data.
     *
     * @param s the barcode content
     * @param symbol the symbol which encoded the content
     * @return the massaged barcode content
     */
    private static String massageZXingData(String s, Symbol symbol) {
        if (symbol instanceof Ean || symbol instanceof Upc) {
            // remove the checksum from the barcode content
            return s.substring(0, s.length() - 1);
        } else if (symbol instanceof DataMatrix &&
                   symbol.getDataType() == DataType.GS1) {
            // remove initial GS + transform subsequent GS -> '[' (Okapi internal representation of FNC1)
            return s.substring(1).replace('\u001d', '[');
        } else {
            // no massaging
            return s;
        }
    }

    /**
     * Massages Okapi barcode content to make it easier to check against ZXing output.
     *
     * @param s the barcode content
     * @param symbol the symbol which encoded the content
     * @return the massaged barcode content
     */
    private static String massageOkapiData(String s, Symbol symbol) {
        if (symbol instanceof Codabar) {
            // remove the start/stop characters from the specified barcode content
            return s.substring(1, s.length() - 1);
        } else if (symbol instanceof Code128) {
            // remove function characters, since ZXing mostly ignores them during read
            return s.replaceAll("[" + Code128.FNC1 + "]", "")
                    .replaceAll("[" + Code128.FNC2 + "]", "")
                    .replaceAll("[" + Code128.FNC3 + "]", "")
                    .replaceAll("[" + Code128.FNC4 + "]", "");
        } else if (symbol instanceof UspsPackage) {
            // remove AI brackets, since ZXing doesn't include them
            return s.replaceAll("[\\[\\]]", "");
        } else {
            // no massaging
            return s;
        }
    }

    /**
     * Verifies that the specified symbol encountered the expected error during encoding.
     *
     * @param actualError the actual error message
     * @throws IOException if there is any I/O error
     */
    private void verifyError(String actualError) throws IOException {
        String expectedError = Files.readAllLines(errorFile.toPath(), UTF_8).get(0);
        assertEquals(expectedError, actualError);
    }

    /**
     * Generates the expectation files for the specified symbol.
     *
     * @param symbol the symbol to generate expectation files for
     * @param actualError the actual error message (may be <tt>null</tt> if there was no error)
     * @throws IOException if there is any I/O error
     */
    private void generateExpectationFiles(Symbol symbol, String actualError) throws IOException {
        if (actualError != null && !actualError.isEmpty()) {
            generateErrorExpectationFile(actualError);
        } else {
            generateCodewordsExpectationFile(symbol);
            generatePngExpectationFile(symbol);
        }
    }

    /**
     * Generates the error expectation file for the specified symbol.
     *
     * @param error the error message
     * @throws IOException if there is any I/O error
     */
    private void generateErrorExpectationFile(String error) throws IOException {
        if (!errorFile.exists()) {
            PrintWriter writer = new PrintWriter(errorFile);
            writer.println(error);
            writer.close();
        }
    }

    /**
     * Generates the codewords expectation file for the specified symbol.
     *
     * @param symbol the symbol to generate codewords for
     * @throws IOException if there is any I/O error
     */
    private void generateCodewordsExpectationFile(Symbol symbol) throws IOException {
        if (!codewordsFile.exists()) {
            PrintWriter writer = new PrintWriter(codewordsFile);
            try {
                int[] codewords = symbol.getCodewords();
                for (int codeword : codewords) {
                    writer.println(codeword);
                }
            } catch (UnsupportedOperationException e) {
                for (String pattern : symbol.pattern) {
                    writer.println(pattern);
                }
            }
            writer.close();
        }
    }

    /**
     * Generates the image expectation file for the specified symbol.
     *
     * @param symbol the symbol to draw
     * @throws IOException if there is any I/O error
     */
    private void generatePngExpectationFile(Symbol symbol) throws IOException {
        if (!pngFile.exists()) {
            BufferedImage img = draw(symbol);
            ImageIO.write(img, "png", pngFile);
        }
    }

    /**
     * Returns the integer contained in the specified string. If the string contains a tab character, it and everything after it
     * is ignored.
     *
     * @param s the string to extract the integer from
     * @return the integer contained in the specified string
     */
    private static int getInt(String s) {
        int i = s.indexOf('\t');
        if (i != -1) {
            s = s.substring(0, i);
        }
        return Integer.parseInt(s);
    }

    /**
     * Draws the specified symbol and returns the resultant image.
     *
     * @param symbol the symbol to draw
     * @return the resultant image
     */
    private static BufferedImage draw(Symbol symbol) {

        int magnification = 10;
        int width = symbol.getWidth() * magnification;
        int height = symbol.getHeight() * magnification;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        g2d.setPaint(Color.WHITE);
        g2d.fillRect(0, 0, width, height);

        Java2DRenderer renderer = new Java2DRenderer(g2d, magnification, Color.WHITE, Color.BLACK);
        renderer.render(symbol);

        g2d.dispose();

        return img;
    }

    /**
     * Initializes the specified symbol using the specified properties, where keys are attribute names and values are attribute
     * values.
     *
     * @param symbol the symbol to initialize
     * @param properties the attribute names and values to set
     * @throws ReflectiveOperationException if there is any reflection error
     */
    private static void setProperties(Symbol symbol, Map< String, String > properties) throws ReflectiveOperationException {
        for (Map.Entry< String, String > entry : properties.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            String setterName = "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
            Method setter = getMethod(symbol.getClass(), setterName);
            invoke(symbol, setter, value);
        }
    }

    /**
     * Returns the method with the specified name in the specified class, or throws an exception if the specified method cannot be
     * found.
     *
     * @param clazz the class to search in
     * @param name the name of the method to search for
     * @return the method with the specified name in the specified class
     */
    private static Method getMethod(Class< ? > clazz, String name) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new RuntimeException("Unable to find method: " + name);
    }

    /**
     * Invokes the specified method on the specified object with the specified parameter.
     *
     * @param object the object to invoke the method on
     * @param setter the method to invoke
     * @param parameter the parameter to pass to the method
     * @throws ReflectiveOperationException if there is any reflection error
     * @throws IllegalArgumentException if the specified parameter is not valid
     */
    @SuppressWarnings("unchecked")
    private static < E extends Enum< E >> void invoke(Object object, Method setter, Object parameter)
                    throws ReflectiveOperationException, IllegalArgumentException {
        Class< ? > paramType = setter.getParameterTypes()[0];
        if (String.class.equals(paramType)) {
            setter.invoke(object, parameter.toString());
        } else if (boolean.class.equals(paramType)) {
            setter.invoke(object, Boolean.valueOf(parameter.toString()));
        } else if (int.class.equals(paramType)) {
            setter.invoke(object, Integer.parseInt(parameter.toString()));
        } else if (double.class.equals(paramType)) {
            setter.invoke(object, Double.parseDouble(parameter.toString()));
        } else if (Character.class.equals(paramType)) {
            setter.invoke(object, parameter.toString().charAt(0));
        } else if (paramType.isEnum()) {
            Class< E > e = (Class< E >) paramType;
            setter.invoke(object, Enum.valueOf(e, parameter.toString()));
        } else {
            throw new RuntimeException("Unknown setter type: " + paramType);
        }
    }

    /**
     * Returns all .properties files in the specified directory, or an empty array if none are found.
     *
     * @param dir the directory to search in
     * @return all .properties files in the specified directory, or an empty array if none are found
     */
    private static File[] getPropertiesFiles(String dir) {

        File[] files = new File(dir).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".properties");
            }
        });

        if (files != null) {
            return files;
        } else {
            return new File[0];
        }
    }

    /**
     * Verifies that the specified images match.
     *
     * @param expected the expected image to check against
     * @param actual the actual image
     * @param failureDirectory the directory to save images to if the assertion fails
     * @throws IOException if there is any I/O error
     */
    private static void assertEqual(BufferedImage expected, BufferedImage actual, File failureDirectory) throws IOException {

        int w = expected.getWidth();
        int h = expected.getHeight();

        if (w != actual.getWidth()) {
            writeImageFilesToFailureDirectory(expected, actual, failureDirectory);
            throw new ComparisonFailure("image width", String.valueOf(w), String.valueOf(actual.getWidth()));
        }

        if (h != actual.getHeight()) {
            writeImageFilesToFailureDirectory(expected, actual, failureDirectory);
            throw new ComparisonFailure("image height", String.valueOf(h), String.valueOf(actual.getHeight()));
        }

        int[] expectedPixels = new int[w * h];
        expected.getRGB(0, 0, w, h, expectedPixels, 0, w);

        int[] actualPixels = new int[w * h];
        actual.getRGB(0, 0, w, h, actualPixels, 0, w);

        for (int i = 0; i < expectedPixels.length; i++) {
            int expectedPixel = expectedPixels[i];
            int actualPixel = actualPixels[i];
            if (expectedPixel != actualPixel) {
                writeImageFilesToFailureDirectory(expected, actual, failureDirectory);
                int x = i % w;
                int y = i / w;
                throw new ComparisonFailure("pixel at " + x + ", " + y, toHexString(expectedPixel), toHexString(actualPixel));
            }
        }
    }

    private static void writeImageFilesToFailureDirectory(BufferedImage expected, BufferedImage actual, File failureDirectory)
        throws IOException {
        mkdirs(failureDirectory);
        ImageIO.write(actual, "png", new File(failureDirectory, "actual.png"));
        ImageIO.write(expected, "png", new File(failureDirectory, "expected.png"));
    }

    /**
     * Extracts test configuration properties from the specified properties file. A single properties file can contain
     * configuration properties for multiple tests.
     *
     * @param propertiesFile the properties file to read
     * @return the test configuration properties in the specified file
     * @throws IOException if there is an error reading the properties file
     */
    private static List< Map< String, String > > readProperties(File propertiesFile) throws IOException {

        String content;
        try {
            byte[] bytes = Files.readAllBytes(propertiesFile.toPath());
            content = decode(bytes, UTF_8);
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 content in file " + propertiesFile.getAbsolutePath(), e);
        }

        String eol = System.lineSeparator();
        String[] lines = content.split(eol);

        List< Map< String, String > > allProperties = new ArrayList<>();
        Map< String, String > properties = new LinkedHashMap<>();

        for (String line : lines) {
            if (line.isEmpty()) {
                // an empty line signals the start of a new test configuration within this single file
                if (!properties.isEmpty()) {
                    allProperties.add(properties);
                    properties = new LinkedHashMap<>();
                }
            } else if (!line.startsWith("#")) {
                int index = line.indexOf('=');
                if (index != -1) {
                    String name = line.substring(0, index);
                    String value = replacePlaceholders(line.substring(index + 1));
                    properties.put(name, value);
                } else {
                    String path = propertiesFile.getAbsolutePath();
                    throw new IOException(path + ": found line '" + line + "' without '=' character; unintentional newline?");
                }
            }
        }

        if (!properties.isEmpty()) {
            allProperties.add(properties);
        }

        return allProperties;
    }

    /**
     * Equivalent to {@link String#String(byte[], Charset)}, except that encoding errors result in runtime errors instead of
     * silent character replacement.
     *
     * @param bytes the bytes to decode
     * @param charset the character set use to decode the bytes
     * @return the specified bytes, as a string
     * @throws CharacterCodingException if there is an error decoding the specified bytes
     */
    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
        return chars.toString();
    }

    /**
     * Replaces any special placeholders supported in test properties files with their raw values.
     *
     * @param s the string to check for placeholders
     * @return the specified string, with placeholders replaced
     */
    private static String replacePlaceholders(String s) {
        return s.replaceAll("\\\\r", "\r")  // "\r" -> CR
                .replaceAll("\\\\n", "\n"); // "\n" -> LF
    }

    /**
     * Finds all test resources and returns the information that JUnit needs to dynamically create the corresponding test cases.
     *
     * @return the test data needed to dynamically create the test cases
     * @throws IOException if there is an error reading a file
     */
    @Parameters(name = "test {index}: {5}: {6}")
    public static List< Object[] > data() throws IOException {

        clear(TEST_FAILURE_IMAGES_DIR);
        mkdirs(TEST_FAILURE_IMAGES_DIR);

        String symbolFilter = System.getProperty("okapi.test.symbol");
        String testNameFilter = System.getProperty("okapi.test.name");

        String backend = "uk.org.okapibarcode.backend";
        Reflections reflections = new Reflections(backend);
        Set< Class< ? extends Symbol >> symbols = reflections.getSubTypesOf(Symbol.class);

        List< Object[] > data = new ArrayList<>();
        for (Class< ? extends Symbol > symbol : symbols) {
            String symbolName = symbol.getSimpleName().toLowerCase();
            if (symbolFilter == null || symbolFilter.isEmpty() || symbolFilter.equalsIgnoreCase(symbolName)) {
                String dir = "src/test/resources/" + backend.replace('.', '/') + "/" + symbolName;
                for (File file : getPropertiesFiles(dir)) {
                    String fileBaseName = file.getName().replaceAll(".properties", "");
                    if (testNameFilter == null || testNameFilter.isEmpty() || testNameFilter.equalsIgnoreCase(fileBaseName)) {
                        File codewordsFile = new File(file.getParentFile(), fileBaseName + ".codewords");
                        File pngFile = new File(file.getParentFile(), fileBaseName + ".png");
                        File errorFile = new File(file.getParentFile(), fileBaseName + ".error");
                        for (Map< String, String > properties : readProperties(file)) {
                            data.add(new Object[] { symbol, properties, codewordsFile, pngFile, errorFile, symbolName, fileBaseName });
                        }
                    }
                }
            }
        }

        return data;
    }

    private static void clear(File dir) {
        if (dir.exists()) {
            for (File file : dir.listFiles()) {
                if (file.isDirectory()) {
                    clear(file);
                }
                if (!file.delete()) {
                    throw new OkapiException("Unable to delete file: " + file);
                }
            }
        }
    }

    private static void mkdirs(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new OkapiException("Unable to create directory: " + dir);
        }
    }
}
