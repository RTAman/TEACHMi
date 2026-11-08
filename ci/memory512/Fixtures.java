import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.util.Random;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
public class Fixtures {
 public static void main(String[] args) throws Exception {
  Path out=Path.of(args[0]); Files.createDirectories(out);
  String text="Depreciation allocates asset cost over its useful life. Straight line annual expense equals cost minus residual value divided by useful life.";
  try(var doc=new PDDocument()) {
   var page=new PDPage();doc.addPage(page);
   try(var c=new PDPageContentStream(doc,page)) { c.beginText();c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);c.newLineAtOffset(40,750);c.showText(text.substring(0,75));c.newLineAtOffset(0,-15);c.showText(text.substring(75));c.endText(); }
   doc.save(out.resolve("small.pdf").toFile());
  }
  try(var deck=new XMLSlideShow()) {deck.createSlide().createTextBox().setText(text);deck.write(Files.newOutputStream(out.resolve("small.pptx")));}
  try(var deck=new HSLFSlideShow()) {var s=deck.createSlide();var b=new HSLFTextBox();b.setText(text);s.addShape(b);deck.write(Files.newOutputStream(out.resolve("small.ppt")));}
  // A valid image-heavy PDF with an exact text source, below the application's 20MiB limit.
  try(var doc=new PDDocument()) {
   var random=new Random(512);
   for(int n=0;n<24;n++) {
    var page=new PDPage();doc.addPage(page);
    var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);
    for(int y=0;y<512;y++) for(int x=0;x<512;x++) image.setRGB(x,y,random.nextInt(0x1000000));
    var encoded=LosslessFactory.createFromImage(doc,image);
    try(var c=new PDPageContentStream(doc,page)) {c.drawImage(encoded,40,40,500,500);c.beginText();c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);c.newLineAtOffset(40,750);c.showText(text.substring(0,75));c.endText();}
   }
   doc.save(out.resolve("large.pdf").toFile());
  }
 }
}
