/*
* Copyright (c) YugaByte, Inc.
*/

package main

import (
    "bytes"
    "encoding/json"
    "fmt"
    "sigs.k8s.io/yaml"
    yaml2 "github.com/goccy/go-yaml"
    "strings"
    "io/ioutil"
    "log"
    "github.com/xeipuuv/gojsonschema"
    "text/template"
    "path/filepath"
    "os"
)

//PlatformAppSecret is special cased because it is not configurable by the user.
var platformAppSecret string = GenerateRandomStringURLSafe(64)

//CorsOrigin is special cased because it is not configurable by the user.
var corsOrigin string = GenerateCORSOrigin()

// RandomDbPassword is applied to the templated configuration file if not already
// set in the configuration file (arbitrary length 20).
var randomDbPassword string = GenerateRandomStringURLSafe(20)

// ValidateJSONSchema checks that the parameters in each component's config file are indeed
// valid by turning the input YAML file into a JSON file, and then validating that
// the parameters have been specified appropriately using the available
// JSON schema.
func validateJSONSchema(filename string) {

    createdBytes, err := ioutil.ReadFile(filename)
    if err != nil {
    log.Fatalf("error: %v", err)
    }

    jsonString, jsonStringErr := yaml.YAMLToJSON(createdBytes)
    if jsonStringErr != nil {
        fmt.Printf("err: %v\n", jsonStringErr)
    }

    var jsonData map[string]interface{}
    if jsonDataError := json.Unmarshal([]byte(jsonString), &jsonData); jsonDataError != nil {
        fmt.Printf("err: %v\n", jsonDataError)
    }

    jsonBytesInput, _ := json.Marshal(jsonData)

    jsonStringInput := string(jsonBytesInput)

    jsonSchemaName := "file://./configFiles/yba-installer-input-json-schema.json"

    schemaLoader := gojsonschema.NewReferenceLoader(jsonSchemaName)
    documentLoader := gojsonschema.NewStringLoader(jsonStringInput)

    result, err := gojsonschema.Validate(schemaLoader, documentLoader)

    // Panic to automatically exit the Templating Phase if the passed-in parameters are
    // not valid.
    if err != nil {
        panic(err.Error())
    }

    if result.Valid() {
        fmt.Printf("The Yba-installer configuration is valid!\n")
    } else {
        fmt.Printf("The Yba-installer configuration is not valid! See Errors :\n")
        for _, desc := range result.Errors() {
            log.Fatalf("- %s\n", desc)
        }
    }

}

// Custom function to return Yaml data that we call from within the templated
// configuration file, to better support future file generation.
func getYamlPathData(text string) (string) {

    inputYml, errYml := ioutil.ReadFile("yba-installer-input.yml")
    if errYml != nil {
        log.Fatalf("error: %v", errYml)
    }

    pathString := strings.ReplaceAll(text, " ", "")

    if strings.Contains(pathString, "appSecret") {
        return platformAppSecret
    } else if strings.Contains(pathString, "corsOrigin") {
        return corsOrigin
    } else {
        yamlPathString := "$" + pathString
        path, err := yaml2.PathString(yamlPathString)
        if err != nil {
            log.Fatalf("Yaml Path string " + yamlPathString + " not valid!")
        }

        var val string
        err = path.Read(bytes.NewReader(inputYml), &val)
        if strings.Contains(pathString, "platformDbPassword") && val == "" {
        return randomDbPassword
    }
    return val
    }
}

// ReadConfigAndTemplate Reads info from input config file and sets
// all template parameters for each individual config file directly, without
// having to rely on variable names in app data.
func readConfigAndTemplate(configYmlFileName string) ([]byte, error)  {

    // First we create a FuncMap with which to register the function.
    funcMap := template.FuncMap{

        // The name "yamlPath" is what the function will be called
        // in the template text.
        "yamlPath": getYamlPathData,
    }

    tmpl, err := template.New(filepath.Base("configFiles/" +configYmlFileName)).
    Funcs(funcMap).ParseFiles("configFiles/" + configYmlFileName)

    if err != nil {
        fmt.Println(err)
        return nil, err
    }

    var buf bytes.Buffer
    if err := tmpl.Execute(&buf, ""); err != nil {
        fmt.Println(err)
        return nil, err
    }

    return buf.Bytes(), nil

}

func readYAMLtoJSON(createdBytes []byte) (map[string]interface{}, error) {

    jsonString, jsonStringErr := yaml.YAMLToJSON(createdBytes)
    if jsonStringErr != nil {
        fmt.Printf("err: %v\n", jsonStringErr)
        return nil, jsonStringErr
    }

    var jsonData map[string]interface{}
    if jsonDataError := json.Unmarshal([]byte(jsonString), &jsonData); jsonDataError != nil {
        fmt.Printf("err: %v\n", jsonDataError)
        return nil, jsonDataError
    }

    return jsonData, nil

}

// WriteBytes writes the byteSlice data to the specified fileName path.
func WriteBytes(byteSlice []byte, fileName []byte) ([]byte, error) {

    fileNameString := string(fileName)

    file, createErr := Create(fileNameString)

    if createErr != nil {
        return nil, createErr
    }

    defer file.Close()
    _, writeErr := file.Write(byteSlice)
    if writeErr != nil {
        return nil, writeErr
    }

    return []byte("Wrote bytes to " + fileNameString + " successfully!"), nil

}

//GenerateTemplatedConfiguration creates the templated configuration files for
//all Yugabyte Anywhere services.
func GenerateTemplatedConfiguration() {

    inputYmlName := "yba-installer-input.yml"

    validateJSONSchema(inputYmlName)

    outputYmlList :=  []string{"yba-installer-prometheus.yml",
    "yba-installer-platform.yml", "yba-installer-nginx.yml"}

    for _, outYmlName := range(outputYmlList) {

        createdBytes, _ := readConfigAndTemplate(outYmlName)

        jsonData, _ := readYAMLtoJSON(createdBytes)

        numberOfServices := len(jsonData["services"].([]interface{}))

        for i := 0; i < numberOfServices; i++ {

        service := jsonData["services"].([]interface{})[i]
        serviceName := fmt.Sprint(service.(map[string]interface{})["name"])

        serviceFileName := fmt.Sprint(service.(map[string]interface{})["fileName"])

        serviceContents := fmt.Sprint(service.(map[string]interface{})["contents"])

        WriteBytes([]byte(serviceContents), []byte(serviceFileName))

        if strings.Contains(serviceFileName, "platform.conf") {

            file, err := os.OpenFile(serviceFileName, os.O_APPEND|os.O_WRONLY, 0644)
            if err != nil {
                log.Println(err)
            }
            defer file.Close()

            // Add the additional raw text to yb-platform.conf if it exists.
            additionalEntryString := strings.TrimSuffix(getYamlPathData(".additional"), "\n")

            if _, err := file.WriteString(additionalEntryString); err != nil {
                log.Fatal(err)
            }

        }

        fmt.Println("Templated configuration for " + serviceName +
        " succesfully applied!")

         }
    }
 }
