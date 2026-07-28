package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// RegisterHTTPHandlers registers all Gin HTTP routes.
func RegisterHTTPHandlers(r *gin.Engine) {
	r.GET("/health", healthHandler)
}

// healthHandler returns the service health status.
func healthHandler(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "ok",
		"service": "risk-service",
	})
}
