FROM openjdk:8-alpine

COPY target/uberjar/bigboard.jar /bigboard/app.jar

EXPOSE 3000

CMD ["java", "-jar", "/bigboard/app.jar"]
