FROM eclipse-temurin:21-jre
WORKDIR /app
CMD ["sh", "-c", "echo app service idle && sleep 3600"]
