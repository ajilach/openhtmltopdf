package com.openhtmltopdf.testcases;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.testlistener.PrintingRunner;
import com.openhtmltopdf.visualtest.TestSupport;

/**
 * Assertions on the PDF/UA tag tree and on annotations.
 *
 * <p>This complements {@link PdfUaTestcaseRunnerTest}, which renders the same testcases
 * but deliberately asserts nothing - it only proves the implementation does not crash and
 * otherwise relies on a human confirming the result in the PDF Accessibility Checker. That
 * is a fine smoke test and a poor oracle: a change that silently produces an invalid tag
 * tree keeps it green.
 *
 * <p>The checks here encode outcomes that were measured against veraPDF, so a regression
 * fails the build instead of being discovered by a validator downstream.
 */
@RunWith(PrintingRunner.class)
public class PdfUaStructureTest {
    private static final String OUT_PATH = "./target/test/pdfua-structure/";

    @BeforeClass
    public static void configure() throws IOException {
        Files.createDirectories(Paths.get(OUT_PATH));

        TestSupport.makeFontFiles();
        TestSupport.quietLogs();
    }

    /**
     * Renders a testcase from {@code /testcases/pdfua/} with accessibility enabled. Any
     * exception propagates: for these cases "it rendered at all" is part of the assertion.
     */
    private static PDDocument render(String testCase) throws IOException {
        byte[] htmlBytes;
        try (InputStream is = PdfUaStructureTest.class.getResourceAsStream("/testcases/pdfua/" + testCase + ".html")) {
            htmlBytes = IOUtils.toByteArray(is);
        }
        String html = new String(htmlBytes, StandardCharsets.UTF_8);

        File output = new File(OUT_PATH, testCase + ".pdf");

        try (FileOutputStream os = new FileOutputStream(output)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.testMode(true);
            builder.usePdfUaAccessbility(true);
            builder.useFont(new File("target/test/visual-tests/Karla-Bold.ttf"), "TestFont");
            builder.withHtmlContent(html, PdfUaStructureTest.class.getResource("/testcases/pdfua/").toString());
            builder.toStream(os);
            builder.run();
        }

        return PDDocument.load(output);
    }

    /**
     * One entry per structure element in document order, carrying its own type and the
     * types of its ancestors, which is what makes containment assertable.
     */
    private static class TaggedElement {
        final String type;
        final List<String> ancestors;

        TaggedElement(String type, List<String> ancestors) {
            this.type = type;
            this.ancestors = ancestors;
        }
    }

    private static List<TaggedElement> tagTree(PDDocument doc) {
        List<TaggedElement> found = new ArrayList<>();
        PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();

        assertTrue("document has no structure tree at all", root != null);

        walk(root, new ArrayList<String>(), found);
        return found;
    }

    private static void walk(PDStructureNode node, List<String> ancestors, List<TaggedElement> found) {
        List<Object> kids = node.getKids();

        if (kids == null) {
            return;
        }

        for (Object kid : kids) {
            if (!(kid instanceof PDStructureElement)) {
                // Marked-content and object references are leaves, not structure.
                continue;
            }

            PDStructureElement element = (PDStructureElement) kid;
            String type = element.getStructureType();

            found.add(new TaggedElement(type, ancestors));

            List<String> childAncestors = new ArrayList<>(ancestors);
            childAncestors.add(type);

            walk(element, childAncestors, found);
        }
    }

    private static long count(List<TaggedElement> tree, String type) {
        long n = 0;
        for (TaggedElement e : tree) {
            if (type.equals(e.type)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Generated content on a list item must not produce a second LI.
     *
     * <p>A {@code ::before} or {@code ::after} box is laid out separately but still reports
     * the element it originates from, so without a guard the tag supplier is asked for a
     * second LI for the same {@code <li>}. That extra LI ends up inside the real item's
     * LBody, giving {@code L > LI > LBody > LI}, which fails PDF/UA-1 7.2 t17 ("LI element
     * should be contained in L element"). Faking a bullet with {@code list-style: none}
     * plus {@code content:} is the usual way to style one, so this hits ordinary documents.
     */
    @Test
    public void generatedContentOnListItemsDoesNotDuplicateTheItem() throws IOException {
        try (PDDocument doc = render("lists")) {
            List<TaggedElement> tree = tagTree(doc);

            // 7.2 t17 is about the IMMEDIATE parent: an LI must sit directly in an L. Testing the
            // whole ancestor chain for LBody instead would reject a legitimately nested list,
            // whose inner items are correctly tagged L > LI > LBody > L > LI.
            for (TaggedElement element : tree) {
                if ("LI".equals(element.type)) {
                    String parent = element.ancestors.isEmpty()
                        ? "(none)"
                        : element.ancestors.get(element.ancestors.size() - 1);

                    assertEquals(
                        "an LI is not directly inside an L, which fails PDF/UA-1 7.2 t17. The duplicate " +
                        "structure element that generated content produces lands in the real item's " +
                        "LBody, giving L > LI > LBody > LI. Ancestors were: " + element.ancestors,
                        "L", parent);
                }
            }

            // lists.html holds 3 + 5 + 4 + 3 (2 plus 1 nested) + 1 items. Asserted so that a
            // change which "fixes" the nesting by dropping items instead fails here.
            assertEquals("unexpected number of list items", 16, count(tree, "LI"));
        }
    }

    /**
     * Every link annotation needs an alternate description in its Contents key.
     *
     * <p>PDF/UA-1 7.18.1 requires any visible annotation to carry one and 7.18.5 requires
     * links to use Contents specifically, so a single undescribed {@code <a href>} makes the
     * whole document invalid. The description is taken from the author's title, else the
     * visible link text, else the alt text of a wrapped image, else the raw URI.
     */
    @Test
    public void everyLinkAnnotationCarriesAnAlternateDescription() throws IOException {
        try (PDDocument doc = render("links")) {
            List<String> descriptions = new ArrayList<>();

            for (PDPage page : doc.getPages()) {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (!(annotation instanceof PDAnnotationLink)) {
                        continue;
                    }

                    String contents = annotation.getContents();

                    assertTrue(
                        "a link annotation has no alternate description in its Contents key, " +
                        "which fails PDF/UA-1 7.18.5 t2 and 7.18.1 t2",
                        contents != null && !contents.trim().isEmpty());

                    descriptions.add(contents);
                }
            }

            assertFalse("links.html produced no link annotations at all", descriptions.isEmpty());

            // One per source of the description, so a broken fallback is named, not just counted.
            assertTrue("description should be taken from the title attribute, got: " + descriptions,
                descriptions.contains("Go to Google!"));
            assertTrue("an internal link should be described too, got: " + descriptions,
                descriptions.contains("Go to end of document."));
            assertTrue("without a title the link text should be used, got: " + descriptions,
                descriptions.contains("its own link text"));
            assertTrue("link text wrapped over several source lines should have its whitespace " +
                "collapsed, otherwise it is read out with the newlines in it, got: " + descriptions,
                descriptions.contains("one two three"));
            assertTrue("an image-only link should fall back to the image's alt text, got: " + descriptions,
                descriptions.contains("Flying Saucer logo"));
        }
    }

    /**
     * A table split over a page break must keep tagging its content.
     *
     * <p>The table and its sections each produce several boxes for one element - one per page
     * - which is legitimate. A guard that turns every repeated box for the same element into
     * a passthrough element leaves the content items on later pages without a parent and the
     * render dies with {@code COSArray.add(null)} inside finishNumberTree. That regression is
     * why the generated-content guard is restricted to inline boxes.
     *
     * <p>{@code tables.html} is the case that actually crashed, so it is the case worth
     * asserting on. {@link PdfUaTestcaseRunnerTest#testTables()} already renders it and would
     * fail on the exception alone; this adds the part that a crash-only test cannot see,
     * namely that the structure really was rebuilt on every page rather than quietly dropped.
     */
    @Test
    public void aTableSplitOverAPageBreakKeepsItsStructure() throws IOException {
        try (PDDocument doc = render("tables")) {
            assertTrue("the testcase is meant to span several pages, otherwise it proves nothing; pages: " +
                doc.getNumberOfPages(), doc.getNumberOfPages() > 1);

            List<TaggedElement> tree = tagTree(doc);

            // Three tables: 2 rows, then a head/body/foot table, then a colspan table.
            assertEquals("a whole table is missing from the tag tree", 3, count(tree, "Table"));
            assertEquals("rows were lost across the page break", 9, count(tree, "TR"));
            assertEquals("header cells were lost across the page break", 2, count(tree, "TH"));
            assertEquals("data cells were lost across the page break", 15, count(tree, "TD"));

            for (TaggedElement element : tree) {
                if (Arrays.asList("TR", "TH", "TD").contains(element.type)) {
                    assertTrue(
                        element.type + " is not inside a Table, so the table's structure was not rebuilt " +
                        "on every page. Ancestors were: " + element.ancestors,
                        element.ancestors.contains("Table"));
                }
            }
        }
    }
}
