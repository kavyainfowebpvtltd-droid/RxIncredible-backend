package com.rxincredible.config;

import com.rxincredible.entity.Order;
import com.rxincredible.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OrderExpirationScheduler {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderExpirationScheduler.class);
    
    private final OrderRepository orderRepository;
    
    public OrderExpirationScheduler(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }
    
    /**
     * Runs every hour to check for unpaid online pharmacy orders that are older than 72 hours
     * and auto-cancel them
     */
    @Scheduled(fixedRate = 3600000) // Run every hour (in milliseconds)
    @Transactional
    public void cancelExpiredOnlinePharmacyOrders() {
        logger.info("Checking for expired online pharmacy orders...");
        
        try {
            // Find all SUBMITTED online pharmacy orders with PENDING payment
            List<Order> expiredOrders = orderRepository.findExpiredOnlinePharmacyOrders(
                "ONLINE_PHARMACY", 
                "SUBMITTED", 
                "PENDING", 
                LocalDateTime.now().minusHours(72)
            );
            
            if (expiredOrders != null && !expiredOrders.isEmpty()) {
                logger.info("Found {} expired online pharmacy orders to cancel", expiredOrders.size());
                
                for (Order order : expiredOrders) {
                    logger.info("Cancelling order: {} (created at: {})", 
                        order.getOrderNumber(), order.getCreatedAt());
                    order.setStatus("CANCELLED");
                    orderRepository.save(order);
                }
                
                logger.info("Successfully cancelled {} expired orders", expiredOrders.size());
            } else {
                logger.info("No expired online pharmacy orders found");
            }
        } catch (Exception e) {
            logger.error("Error while checking for expired orders: {}", e.getMessage(), e);
        }
    }
}