package handler

import (
	"bytes"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/gin-gonic/gin"
)

// POST /api/chain — Path A: risk → clearing → audit → archive
func handleChain(c *gin.Context) {
	start := time.Now()
	time.Sleep(10 * time.Millisecond)
	log.Printf("[%s] risk-service /api/chain", time.Now().Format(time.RFC3339))

	clearingURL := os.Getenv("CLEARING_SERVICE_URL")
	if clearingURL == "" {
		clearingURL = "http://clearing-service:8080"
	}

	// Call clearing-service with NO headers forwarded
	resp, err := http.Post(clearingURL+"/api/chain", "application/json", bytes.NewReader([]byte("{}")))
	if err != nil {
		c.JSON(http.StatusOK, gin.H{
			"service":    "risk-service",
			"elapsed_ms": time.Since(start).Milliseconds(),
			"downstream": gin.H{"error": err.Error()},
		})
		return
	}
	defer resp.Body.Close()

	var clearingResult map[string]interface{}
	json.NewDecoder(resp.Body).Decode(&clearingResult)

	c.JSON(http.StatusOK, gin.H{
		"service":    "risk-service",
		"elapsed_ms": time.Since(start).Milliseconds(),
		"downstream": clearingResult,
	})
}

// RegisterChainRoutes registers Path A routes
func RegisterChainRoutes(r *gin.Engine) {
	r.POST("/api/chain", handleChain)
}
