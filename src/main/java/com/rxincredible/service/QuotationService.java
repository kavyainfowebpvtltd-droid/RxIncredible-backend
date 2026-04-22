
package com.rxincredible.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rxincredible.entity.Order;
import com.rxincredible.entity.Quotation;
import com.rxincredible.entity.User;
import com.rxincredible.repository.OrderRepository;
import com.rxincredible.repository.QuotationRepository;
import com.rxincredible.repository.UserRepository;
import com.rxincredible.util.CurrencyUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class QuotationService {

    private static final Logger logger = LoggerFactory.getLogger(QuotationService.class);
    private static final DateTimeFormatter QUOTATION_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final float PW = PDRectangle.A4.getWidth();
    private static final float PH = PDRectangle.A4.getHeight();
    private static final float M = 42f;
    private static final float CW = PW - (M * 2);
    private static final float GAP = 20f;
    private static final float FOOT = 92f;
    private static final int[] PRIMARY = { 30, 58, 138 };
    private static final int[] PRIMARY_DARK = { 16, 42, 67 };
    private static final int[] TEXT = { 36, 59, 83 };
    private static final int[] MUTED = { 100, 116, 139 };
    private static final int[] BORDER = { 214, 224, 235 };
    private static final int[] WHITE = { 255, 255, 255 };
    private static final int[] SOFT = { 243, 247, 255 };
    private static final int[] SOFT_ALT = { 248, 250, 252 };
    private static final int[] ACCENT = { 221, 235, 255 };

    private record TableResult(PDPageContentStream stream, float y) {
    }

    private final QuotationRepository quotationRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuotationService(QuotationRepository quotationRepository, UserRepository userRepository,
            OrderRepository orderRepository, EmailService emailService, BillService billService) {
        this.quotationRepository = quotationRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.emailService = emailService;
    }

    @Transactional
    public Quotation createQuotation(Quotation quotation, Long userId, Long createdById, Long orderId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
        User createdBy = userRepository.findById(createdById)
                .orElseThrow(() -> new RuntimeException("Creator not found"));
        Order order = orderId != null
                ? orderRepository.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"))
                : null;

        Quotation targetQuotation = orderId != null
                ? quotationRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(new Quotation())
                : new Quotation();

        if (targetQuotation.getQuotationNumber() == null || targetQuotation.getQuotationNumber().isBlank()) {
            targetQuotation.setQuotationNumber("QUO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }

        targetQuotation.setUser(user);
        targetQuotation.setCreatedBy(createdBy);
        targetQuotation.setOrder(order);
        targetQuotation.setItems(quotation.getItems());
        targetQuotation.setSubtotal(quotation.getSubtotal());
        targetQuotation.setTax(quotation.getTax());
        targetQuotation.setDiscount(quotation.getDiscount());
        targetQuotation.setTotalAmount(quotation.getTotalAmount());
        targetQuotation.setStatus(
                quotation.getStatus() != null && !quotation.getStatus().isBlank() ? quotation.getStatus() : "DRAFT");

        if (targetQuotation.getEmailSent() == null) {
            targetQuotation.setEmailSent(false);
        }

        if (order != null && quotation.getTotalAmount() != null) {
            order.setTotalAmount(quotation.getTotalAmount());
            orderRepository.save(order);
        }

        return quotationRepository.save(targetQuotation);
    }

    public List<Quotation> findAllQuotations() {
        return quotationRepository.findAllWithUserAndCreatorDetails();
    }

    public Optional<Quotation> findById(Long id) {
        return quotationRepository.findById(id);
    }

    public Optional<Quotation> findByIdWithOrder(Long id) {
        return quotationRepository.findByIdWithOrder(id);
    }

    public Optional<Quotation> findByQuotationNumber(String quotationNumber) {
        return quotationRepository.findByQuotationNumber(quotationNumber);
    }

    public List<Quotation> findByUserId(Long userId) {
        return quotationRepository.findUserQuotations(userId);
    }

    public Optional<Quotation> findByOrderId(Long orderId) {
        return quotationRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId);
    }

    public Optional<Quotation> findByOrderIdWithDetails(Long orderId) {
        List<Quotation> quotations = quotationRepository.findAllByOrderIdWithDetails(orderId);
        return quotations.isEmpty() ? Optional.empty() : Optional.of(quotations.get(0));
    }

    public List<Quotation> findByStatus(String status) {
        return quotationRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public Quotation updateStatus(Long id, String status) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Quotation not found"));
        quotation.setStatus(status);
        return quotationRepository.save(quotation);
    }

    @Transactional
    public void deleteQuotation(Long id) {
        quotationRepository.deleteById(id);
    }

    public void sendQuotationEmail(Long quotationId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new RuntimeException("Quotation not found"));

        if (Boolean.TRUE.equals(quotation.getEmailSent())) {
            throw new RuntimeException("Quotation email has already been sent");
        }

        Order order = quotation.getOrder();
        if (order == null)
            throw new RuntimeException("No order associated with this quotation");
        try {
            if (quotation.getTotalAmount() != null
                    && (order.getTotalAmount() == null || order.getTotalAmount().compareTo(quotation.getTotalAmount()) != 0)) {
                order.setTotalAmount(quotation.getTotalAmount());
                order = orderRepository.save(order);
            }

            byte[] quotationPdf = generateQuotationPdf(quotation, order);
            emailService.sendQuotationEmail(order, quotationPdf, "Quotation_" + order.getOrderNumber() + ".pdf");
            quotation.setEmailSent(true);
            quotationRepository.save(quotation);
            logger.info("Quotation email sent for quotation {} and order {}", quotation.getQuotationNumber(),
                    order.getOrderNumber());
        } catch (Exception e) {
            logger.error("Failed to send quotation email for quotation {}: {}", quotation.getQuotationNumber(),
                    e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public byte[] downloadQuotationPdf(Long quotationId) {
        Quotation quotation = quotationRepository.findByIdWithOrder(quotationId)
                .orElseGet(() -> quotationRepository.findById(quotationId)
                        .orElseThrow(() -> new RuntimeException("Quotation not found")));
        Order order = quotation.getOrder();
        if (order == null) {
            throw new RuntimeException("No order associated with this quotation");
        }
        try {
            return generateQuotationPdf(quotation, order);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate quotation PDF", e);
        }
    }

    public byte[] downloadQuotationPdfByOrderId(Long orderId) {
        Quotation quotation = findByOrderIdWithDetails(orderId)
                .orElseGet(() -> findByOrderId(orderId)
                        .orElseThrow(() -> new RuntimeException("Quotation not found")));
        return downloadQuotationPdf(quotation.getId());
    }

    private byte[] generateQuotationPdf(Quotation quotation, Order order) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(document, page);

            List<Map<String, Object>> items = parseItems(quotation.getItems());
            User user = quotation.getUser() != null ? quotation.getUser() : order.getUser();
            String patientName = safe(user != null ? user.getFullName() : null);
            String patientEmail = safe(user != null ? user.getEmail() : order.getUserEmail());
            String patientPhone = safe(resolvePhone(order, user));
            String customerAddress = buildCustomerAddress(order, user);
            String quotationDate = quotation.getCreatedAt() != null
                    ? quotation.getCreatedAt().format(QUOTATION_DATE_FORMAT)
                    : "-";
            String serviceType = formatServiceType(order.getServiceType());
            String generatedAt = LocalDateTime.now().format(GENERATED_AT_FORMAT);
            String country = resolveCountry(order, user);

            BigDecimal calculatedSubtotal = items.stream().map(this::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal subtotal = firstNonZero(quotation.getSubtotal(), calculatedSubtotal);
            BigDecimal totalAmount = firstNonZero(quotation.getTotalAmount(), subtotal);
            BigDecimal deliveryCharge = totalAmount.subtract(subtotal).max(BigDecimal.ZERO);

            float y = drawHeader(document, cs);
            y = drawCards(cs, y - GAP, patientName, patientEmail, patientPhone, customerAddress,
                    quotation.getQuotationNumber(), quotationDate, serviceType);
            y = y - GAP;

            TableResult table = drawTable(document, cs, y - 12f, items, country);
            PDPageContentStream active = table.stream();
            y = table.y();

            if (y - 140 < M + FOOT) {
                active.close();
                PDPage summaryPage = new PDPage(PDRectangle.A4);
                document.addPage(summaryPage);
                active = new PDPageContentStream(document, summaryPage);
                y = PH - M;
            }

            drawSummary(active, y - GAP, subtotal, deliveryCharge, totalAmount, country);
            drawFooter(document, active, generatedAt);
            active.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    private float drawHeader(PDDocument document, PDPageContentStream cs) throws IOException {
        float top = PH - M;
        float headerBottom = top - 88f;
        rect(cs, M, headerBottom, CW, 88f, WHITE, true);
        rect(cs, M, headerBottom, CW, 88f, BORDER, false, 0.9f);
        rect(cs, M, headerBottom, 8f, 88f, PRIMARY, true);

        drawLogo(document, cs, M + 14f, top - 74f, 78f, 62f,
                "backend\\src\\main\\java\\com\\rxincredible\\service\\asset\\logo1.png");

        float x = M + 98f;
        text(cs, "Bhagyawati Drugs & Chemicals Pvt. Ltd", x, top - 18f, PDType1Font.HELVETICA_BOLD, 17, PRIMARY_DARK);
        text(cs, "234 Shree Nagar, Nagpur - 440015, Maharashtra, India", x, top - 37f, PDType1Font.HELVETICA, 9, MUTED);
        text(cs, "Email: contact@rxincredible.com | Phone: 9822848689", x, top - 51f, PDType1Font.HELVETICA, 9, MUTED);
        text(cs, "GST Number: 27AALCB2082P2Z4", x, top - 65f, PDType1Font.HELVETICA_BOLD, 9, PRIMARY);

        float badgeW = 132f;
        float badgeH = 38f;
        float bx = PW - M - badgeW - 8f;
        float badgeY = headerBottom + 4f;
        rect(cs, bx, badgeY, badgeW, badgeH, PRIMARY_DARK, true);
        center(cs, "QUOTATION", bx, badgeY + 15f, badgeW, PDType1Font.HELVETICA_BOLD, 16, WHITE);
        line(cs, M, headerBottom, PW - M, headerBottom, PRIMARY, 1.4f);
        return headerBottom;
    }

    private float drawCards(PDPageContentStream cs, float top, String patientName, String patientEmail,
            String patientPhone, String address, String quotationNumber, String quotationDate, String serviceType)
            throws IOException {
        float w = (CW - 18f) / 2f;
        float leftX = M;
        float rightX = leftX + w + 18f;
        float inner = w - 28f;
        List<String> emails = wrap(patientEmail, inner, PDType1Font.HELVETICA, 9);
        List<String> phones = wrap(patientPhone, inner, PDType1Font.HELVETICA, 9);
        List<String> addresses = wrap(address, inner, PDType1Font.HELVETICA, 9);
        float leftH = 26f + 20f + emails.size() * 12f + phones.size() * 12f + addresses.size() * 12f + 16f;
        float rightH = 26f + 4 * 18f + 18f;
        float h = Math.max(132f, Math.max(leftH, rightH));
        float y = top - h;
        card(cs, leftX, y, w, h);
        card(cs, rightX, y, w, h);

        sectionLabel(cs, "Customer Details", leftX + 14f, top - 18f);
        text(cs, patientName, leftX + 14f, top - 40f, PDType1Font.HELVETICA_BOLD, 13, PRIMARY_DARK);
        float ly = top - 58f;
        ly = lines(cs, emails, leftX + 14f, ly, PDType1Font.HELVETICA, 9, MUTED, 12f);
        ly = lines(cs, phones, leftX + 14f, ly, PDType1Font.HELVETICA, 9, MUTED, 12f);
        lines(cs, addresses, leftX + 14f, ly, PDType1Font.HELVETICA, 9, MUTED, 12f);

        sectionLabel(cs, "Quotation Details", rightX + 14f, top - 18f);
        float ry = top - 40f;
        ry = detail(cs, rightX + 14f, ry, w - 28f, "Quotation Number", quotationNumber, true);
        ry = detail(cs, rightX + 14f, ry, w - 28f, "Date", quotationDate, false);
        ry = detail(cs, rightX + 14f, ry, w - 28f, "Service Type", serviceType, false);
        detail(cs, rightX + 14f, ry, w - 28f, "Payment Terms", "Due on Receipt", false);
        return y;
    }

    private float drawTag(PDPageContentStream cs, float top, String title) throws IOException {
        float y = top - 28f;
        rect(cs, M, y, 162f, 28f, ACCENT, true);
        rect(cs, M, y, 162f, 28f, BORDER, false, 0.7f);
        rect(cs, M, y + 4f, 5f, 20f, PRIMARY, true);
        text(cs, title, M + 16f, y + 9f, PDType1Font.HELVETICA_BOLD, 11, PRIMARY_DARK);
        return y;
    }

    private TableResult drawTable(PDDocument document, PDPageContentStream cs, float top,
            List<Map<String, Object>> items, String country) throws IOException {
        float[] widths = { 36f, 224f, 72f, 44f, 78f, 85f };
        float rateRightX = M + widths[0] + widths[1] + widths[2] + widths[3] + widths[4] - 10f;
        float amountRightX = M + widths[0] + widths[1] + widths[2] + widths[3] + widths[4] + widths[5] - 10f;
        float y = top;
        PDPageContentStream stream = cs;
        headerRow(stream, y, widths);
        y -= 28f;

        if (items.isEmpty()) {
            rect(stream, M, y - 30f, CW, 30f, SOFT_ALT, true);
            rect(stream, M, y - 30f, CW, 30f, BORDER, false, 0.6f);
            text(stream, "No medicine items available.", M + 12f, y - 19f, PDType1Font.HELVETICA, 9, TEXT);
            return new TableResult(stream, y - 30f);
        }

        int index = 1;
        for (Map<String, Object> item : items) {
            String name = safe(item.get("name"));
            String brand = normalize(item.get("brand"));
            if (!brand.isBlank() && !"-".equals(brand))
                name = name + " (" + brand + ")";
            List<String> wrapped = wrap(name, widths[1] - 18f, PDType1Font.HELVETICA, 9);
            float rowH = Math.max(34f, 16f + wrapped.size() * 12f);

            if (y - rowH < M + 20f) {
                stream.close();
                PDPage p = new PDPage(PDRectangle.A4);
                document.addPage(p);
                stream = new PDPageContentStream(document, p);
                float nextTop = PH - M - 12f;
                y = nextTop;
                headerRow(stream, y, widths);
                y -= 28f;
            }

            float x = M;
            int[] fill = index % 2 == 1 ? SOFT_ALT : WHITE;
            for (float width : widths) {
                rect(stream, x, y - rowH, width, rowH, fill, true);
                rect(stream, x, y - rowH, width, rowH, BORDER, false, 0.45f);
                x += width;
            }

            float cy = y - (rowH / 2f) + 3f;
            center(stream, String.valueOf(index), M, cy, widths[0], PDType1Font.HELVETICA, 9, TEXT);
            lines(stream, wrapped, M + widths[0] + 9f, y - 16f, PDType1Font.HELVETICA, 9, TEXT, 12f);
            center(stream, safe(item.get("dosage")), M + widths[0] + widths[1], cy, widths[2], PDType1Font.HELVETICA, 9,
                    TEXT);
            center(stream, safe(item.get("quantity")), M + widths[0] + widths[1] + widths[2], cy, widths[3],
                    PDType1Font.HELVETICA, 9, TEXT);
            rightFit(stream, money(num(item.get("pricePerUnit")), country), rateRightX, cy, widths[4] - 18f,
                    PDType1Font.HELVETICA, 9, 7, TEXT);
            rightFit(stream, money(amount(item), country), amountRightX, cy, widths[5] - 18f,
                    PDType1Font.HELVETICA_BOLD, 9, 7, PRIMARY_DARK);
            y -= rowH;
            index++;
        }
        return new TableResult(stream, y);
    }

    private void drawSummary(PDPageContentStream cs, float top, BigDecimal subtotal, BigDecimal delivery,
            BigDecimal total, String country) throws IOException {
        float leftW = 248f;
        float rightW = CW - leftW - 20f;
        float blockY = top - 110f;
        card(cs, M, blockY + 52f, leftW, 58f);
        text(cs, "Prepared by", M + 16f, blockY + 96f, PDType1Font.HELVETICA_BOLD, 9, MUTED);
        wrapText(cs, "Bhagyawati Drugs & Chemicals Pvt. Ltd.", M + 16f, blockY + 80f, leftW - 32f,
                PDType1Font.HELVETICA, 9, PRIMARY_DARK, 12f);

        float summaryX = M + leftW + 20f;
        float summaryY = blockY;
        float summaryH = 110f;
        float summaryPad = 16f;
        float summaryInnerX = summaryX + summaryPad;
        float summaryInnerW = rightW - (summaryPad * 2f);
        rect(cs, summaryX, summaryY, rightW, summaryH, WHITE, true);
        rect(cs, summaryX, summaryY, rightW, summaryH, BORDER, false, 0.9f);
        rect(cs, summaryInnerX - 8f, summaryY + 84f, 4f, 20f, PRIMARY, true);
        text(cs, "Amount Summary", summaryInnerX + 2f, summaryY + 92f, PDType1Font.HELVETICA_BOLD, 11, PRIMARY_DARK);
        summary(cs, summaryInnerX, summaryY + 70f, summaryInnerW, "Subtotal", money(subtotal, country), false);
        summary(cs, summaryInnerX, summaryY + 52f, summaryInnerW, "Delivery", money(delivery, country), false);

        float totalBarX = summaryInnerX - 4f;
        float totalBarW = summaryInnerW + 8f;
        rect(cs, totalBarX, summaryY + 12f, totalBarW, 28f, ACCENT, true);
        rect(cs, totalBarX, summaryY + 12f, totalBarW, 28f, BORDER, false, 0.6f);
        summary(cs, summaryInnerX + 6f, summaryY + 22f, summaryInnerW - 12f, "Grand Total", money(total, country), true);
    }

    private void drawFooter(PDDocument document, PDPageContentStream cs, String generatedAt) throws IOException {
        float footerY = 24f;
        float footerH = 60f;
        line(cs, M, footerY + footerH + 10f, PW - M, footerY + footerH + 10f, PRIMARY, 1.1f);
        rect(cs, M, footerY, CW, footerH, SOFT_ALT, true);
        rect(cs, M, footerY, CW, footerH, BORDER, false, 0.8f);

        float footerLogoY = footerY + 6f;
        drawLogo(document, cs, M + 10f, footerLogoY, 88f, 48f,
                "backend\\src\\main\\java\\com\\rxincredible\\service\\asset\\logo2.png");

        float textX = M + 112f;
        float footerTextY = footerY + 38f;
        List<String> footerLines = List.of(
                "This is a computer-generated receipt and does not require a signature.",
                "For any queries, contact us at contact@rxincredible.com | Phone: 9822848689",
                "Generated by Bhagyawati Drugs & Chemicals Pvt. Ltd " + generatedAt);
        lines(cs, footerLines, textX, footerTextY, PDType1Font.HELVETICA, 8, MUTED, 12f);
    }

    private void headerRow(PDPageContentStream cs, float top, float[] w) throws IOException {
        float x = M;
        for (float width : w) {
            rect(cs, x, top - 26f, width, 26f, PRIMARY, true);
            rect(cs, x, top - 26f, width, 26f, PRIMARY_DARK, false, 0.4f);
            x += width;
        }
        float y = top - 17f;
        center(cs, "Sr No", M, y, w[0], PDType1Font.HELVETICA_BOLD, 9, WHITE);
        text(cs, "Medicine Name", M + w[0] + 9f, y, PDType1Font.HELVETICA_BOLD, 9, WHITE);
        center(cs, "Dosage", M + w[0] + w[1], y, w[2], PDType1Font.HELVETICA_BOLD, 9, WHITE);
        center(cs, "Qty", M + w[0] + w[1] + w[2], y, w[3], PDType1Font.HELVETICA_BOLD, 9, WHITE);
        right(cs, "Rate", M + w[0] + w[1] + w[2] + w[3] + w[4] - 10f, y, PDType1Font.HELVETICA_BOLD, 9, WHITE);
        right(cs, "Amount", M + w[0] + w[1] + w[2] + w[3] + w[4] + w[5] - 10f, y, PDType1Font.HELVETICA_BOLD, 9,
                WHITE);
    }

    private float detail(PDPageContentStream cs, float x, float y, float w, String label, String value, boolean strong)
            throws IOException {
        text(cs, label, x, y, PDType1Font.HELVETICA, 9, MUTED);
        right(cs, safe(value), x + w, y, strong ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, 9,
                strong ? PRIMARY_DARK : TEXT);
        return y - 18f;
    }

    private void summary(PDPageContentStream cs, float x, float y, float w, String label, String value, boolean strong)
            throws IOException {
        text(cs, label, x, y, strong ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, strong ? 11 : 9,
                strong ? PRIMARY : TEXT);
        right(cs, value, x + w, y, strong ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, strong ? 11 : 9,
                strong ? PRIMARY : TEXT);
    }

    private void sectionLabel(PDPageContentStream cs, String label, float x, float y) throws IOException {
        rect(cs, x - 4f, y - 4f, 4f, 24f, PRIMARY, true);
        rect(cs, x - 4f, y - 4f, 4f, 24f, PRIMARY_DARK, false, 0.5f);
        text(cs, label, x + 6f, y + 6f, PDType1Font.HELVETICA_BOLD, 12, PRIMARY_DARK);
    }

    private void card(PDPageContentStream cs, float x, float y, float w, float h) throws IOException {
        rect(cs, x, y, w, h, SOFT, true);
        rect(cs, x, y, w, h, BORDER, false, 0.85f);
    }

    private void drawLogo(PDDocument document, PDPageContentStream cs, float x, float y, float maxW, float maxH,
            String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                logger.warn("Logo file not found: {}", path);
                return;
            }
            PDImageXObject image = PDImageXObject.createFromFile(file.getAbsolutePath(), document);
            float scale = Math.min(maxW / image.getWidth(), maxH / image.getHeight());
            float drawW = image.getWidth() * scale;
            float drawH = image.getHeight() * scale;
            float drawX = x + ((maxW - drawW) / 2f);
            float drawY = y + ((maxH - drawH) / 2f);
            cs.drawImage(image, drawX, drawY, drawW, drawH);
        } catch (Exception e) {
            logger.debug("Unable to load quotation logo from {}: {}", path, e.getMessage());
        }
    }

    private void text(PDPageContentStream cs, String value, float x, float y, PDFont font, int size, int[] color)
            throws IOException {
        cs.beginText();
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(normalize(value));
        cs.endText();
    }

    private void right(PDPageContentStream cs, String value, float rightX, float y, PDFont font, int size, int[] color)
            throws IOException {
        String text = normalize(value);
        float width = font.getStringWidth(text) / 1000f * size;
        text(cs, text, rightX - width, y, font, size, color);
    }

    private void rightFit(PDPageContentStream cs, String value, float rightX, float y, float maxW, PDFont font,
            int preferredSize, int minSize, int[] color) throws IOException {
        String text = normalize(value);
        int size = preferredSize;
        while (size > minSize && ((font.getStringWidth(text) / 1000f) * size) > maxW) {
            size--;
        }
        right(cs, text, rightX, y, font, size, color);
    }

    private void center(PDPageContentStream cs, String value, float boxX, float y, float boxW, PDFont font, int size,
            int[] color) throws IOException {
        String text = normalize(value);
        float width = font.getStringWidth(text) / 1000f * size;
        text(cs, text, boxX + ((boxW - width) / 2f), y, font, size, color);
    }

    private void wrapText(PDPageContentStream cs, String value, float x, float y, float maxW, PDFont font, int size,
            int[] color, float lineH) throws IOException {
        lines(cs, wrap(value, maxW, font, size), x, y, font, size, color, lineH);
    }

    private float lines(PDPageContentStream cs, List<String> values, float x, float y, PDFont font, int size,
            int[] color, float lineH) throws IOException {
        float current = y;
        for (String value : values) {
            text(cs, value, x, current, font, size, color);
            current -= lineH;
        }
        return current;
    }

    private List<String> wrap(String value, float maxW, PDFont font, int size) throws IOException {
        String text = normalize(value);
        if (text.isBlank())
            return List.of("-");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            float width = font.getStringWidth(candidate) / 1000f * size;
            if (width <= maxW || current.isEmpty())
                current = new StringBuilder(candidate);
            else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty())
            lines.add(current.toString());
        return lines;
    }

    private void rect(PDPageContentStream cs, float x, float y, float w, float h, int[] color, boolean fill)
            throws IOException {
        rect(cs, x, y, w, h, color, fill, 1f);
    }

    private void rect(PDPageContentStream cs, float x, float y, float w, float h, int[] color, boolean fill, float lw)
            throws IOException {
        if (fill)
            cs.setNonStrokingColor(color[0], color[1], color[2]);
        else {
            cs.setStrokingColor(color[0], color[1], color[2]);
            cs.setLineWidth(lw);
        }
        cs.addRect(x, y, w, h);
        if (fill)
            cs.fill();
        else
            cs.stroke();
    }

    private void line(PDPageContentStream cs, float x1, float y1, float x2, float y2, int[] color, float lw)
            throws IOException {
        cs.setStrokingColor(color[0], color[1], color[2]);
        cs.setLineWidth(lw);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private List<Map<String, Object>> parseItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank())
            return List.of();
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            logger.warn("Failed to parse quotation items JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildCustomerAddress(Order order, User user) {
        List<String> parts = new ArrayList<>();
        if (order != null) {
            add(parts, order.getDeliveryAddress());
            String cityState = join(order.getDeliveryCity(), order.getDeliveryState());
            if (!cityState.isBlank())
                add(parts, cityState + (blank(order.getDeliveryPincode()) ? "" : " - " + order.getDeliveryPincode()));
            else
                add(parts, order.getDeliveryPincode());
            add(parts, order.getDeliveryCountry());
        }
        if (parts.isEmpty() && user != null) {
            add(parts, user.getAddress());
            String cityState = join(user.getCity(), user.getState());
            if (!cityState.isBlank())
                add(parts, cityState + (blank(user.getPincode()) ? "" : " - " + user.getPincode()));
            else
                add(parts, user.getPincode());
            add(parts, user.getCountry());
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    private String resolvePhone(Order order, User user) {
        if (order != null && !blank(order.getDeliveryPhone()))
            return order.getDeliveryPhone();
        if (user != null && !blank(user.getPhone()))
            return user.getPhone();
        if (user != null && !blank(user.getDeliveryPhone()))
            return user.getDeliveryPhone();
        return "-";
    }

    private String formatServiceType(String serviceType) {
        if (blank(serviceType))
            return "-";
        List<String> words = new ArrayList<>();
        for (String word : serviceType.replace('_', ' ').toLowerCase(Locale.ENGLISH).split("\\s+")) {
            if (!word.isBlank())
                words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return String.join(" ", words);
    }

    private BigDecimal num(Object value) {
        if (value == null)
            return BigDecimal.ZERO;
        if (value instanceof BigDecimal big)
            return big;
        if (value instanceof Number number)
            return BigDecimal.valueOf(number.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal amount(Map<String, Object> item) {
        return num(item.get("quantity")).multiply(num(item.get("pricePerUnit")));
    }

    private String money(BigDecimal value, String country) {
        return CurrencyUtil.formatAmount((value != null ? value : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                country);
    }

    private String resolveCountry(Order order, User user) {
        if (order != null && !blank(order.getDeliveryCountry())) {
            return order.getDeliveryCountry();
        }
        if (user != null && !blank(user.getCountry())) {
            return user.getCountry();
        }
        return "India";
    }

    private BigDecimal firstNonZero(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null && preferred.compareTo(BigDecimal.ZERO) > 0 ? preferred
                : (fallback != null ? fallback : BigDecimal.ZERO);
    }

    private void add(List<String> parts, String value) {
        if (!blank(value) && !"-".equals(value.trim()))
            parts.add(value.trim());
    }

    private String join(String a, String b) {
        List<String> parts = new ArrayList<>();
        add(parts, a);
        add(parts, b);
        return String.join(", ", parts);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(Object value) {
        return (value == null ? "-" : String.valueOf(value)).replace("\n", " ").replace("\r", " ");
    }

    private String normalize(Object value) {
        return safe(value).replace("₹", "Rs.").replace("•", "-").replaceAll("[^\\x20-\\x7E]", "");
    }
}
