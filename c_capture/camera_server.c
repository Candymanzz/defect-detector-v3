#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <arv.h>
#include <microhttpd.h>
#include <jpeglib.h>

typedef struct {
    ArvCamera *camera;
    ArvStream *stream;
} CameraContext;

static unsigned char* capture_frame(CameraContext *ctx, unsigned long *jpeg_size) {
    // 1) Сбрасываем все старые кадры, накопленные до текущего HTTP-запроса.
    ArvBuffer *stale = NULL;
    while ((stale = arv_stream_try_pop_buffer(ctx->stream)) != NULL) {
        arv_stream_push_buffer(ctx->stream, stale);
    }

    // 2) Ждем следующий кадр после очистки очереди (то есть "свежий" кадр).
    ArvBuffer *buffer = arv_stream_timeout_pop_buffer(ctx->stream, 1000000);
    if (!buffer) return NULL;

    if (arv_buffer_get_status(buffer) != ARV_BUFFER_STATUS_SUCCESS) {
        arv_stream_push_buffer(ctx->stream, buffer);
        return NULL;
    }

    size_t size;
    const uint8_t *data = (const uint8_t*)arv_buffer_get_data(buffer, &size);
    
    // Берем параметры кадра прямо из буфера
    int width = arv_buffer_get_image_width(buffer);
    int height = arv_buffer_get_image_height(buffer);

    struct jpeg_compress_struct cinfo;
    struct jpeg_error_mgr jerr;
    unsigned char *jpeg_buffer = NULL;

    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_compress(&cinfo);
    jpeg_mem_dest(&cinfo, &jpeg_buffer, jpeg_size);

    cinfo.image_width = width;
    cinfo.image_height = height;
    cinfo.input_components = 1; 
    cinfo.in_color_space = JCS_GRAYSCALE;

    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, 85, TRUE);
    jpeg_start_compress(&cinfo, TRUE);

    JSAMPROW row_pointer;
    while (cinfo.next_scanline < cinfo.image_height) {
        // Защита: адрес начала каждой строки
        row_pointer = (JSAMPROW) &data[cinfo.next_scanline * width];
        jpeg_write_scanlines(&cinfo, &row_pointer, 1);
    }

    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);

    // Возвращаем буфер камере для повторного использования
    arv_stream_push_buffer(ctx->stream, buffer);

    return jpeg_buffer;
}

static enum MHD_Result handle_request(void *cls, struct MHD_Connection *connection,
                                     const char *url, const char *method, const char *version,
                                     const char *upload_data, size_t *upload_data_size, void **con_cls) {
    CameraContext *ctx = cls;
    unsigned long jpeg_size = 0;
    unsigned char *jpeg = NULL;
    for (int attempt = 0; attempt < 3; attempt++) {
        jpeg = capture_frame(ctx, &jpeg_size);
        if (jpeg) break;
        usleep(50000);
    }

    if (!jpeg) {
        const char *msg = "Camera Timeout or Frame Error";
        struct MHD_Response *response = MHD_create_response_from_buffer(strlen(msg), (void*)msg, MHD_RESPMEM_PERSISTENT);
        MHD_add_response_header(response, "Content-Type", "text/plain; charset=utf-8");
        enum MHD_Result ret = MHD_queue_response(connection, 503, response);
        MHD_destroy_response(response);
        return ret;
    }

    // MHD_RESPMEM_MUST_FREE скажет серверу очистить память jpeg после отправки (выделенную в jpeg_mem_dest)
    struct MHD_Response *response = MHD_create_response_from_buffer(jpeg_size, jpeg, MHD_RESPMEM_MUST_FREE);
    MHD_add_response_header(response, "Content-Type", "image/jpeg");
    MHD_add_response_header(response, "Access-Control-Allow-Origin", "*"); // Для доступа из других сервисов
    enum MHD_Result ret = MHD_queue_response(connection, 200, response);
    MHD_destroy_response(response);

    return ret;
}

int main() {
    arv_update_device_list();
    GError *error = NULL;

    // 1. Поиск и открытие камеры
    ArvCamera *camera = arv_camera_new(NULL, &error);
    if (!camera) {
        fprintf(stderr, "Camera error: %s\n", error ? error->message : "No camera found");
        return 1;
    }

    // 2. Настройка сетевых параметров через ArvDevice (фикс ошибки компиляции)
    ArvDevice *device = arv_camera_get_device(camera);
    if (device) {
        // Устанавливаем размер пакета 1500 для совместимости
        arv_device_set_integer_feature_value(device, "GevSCPSPacketSize", 1500, NULL);
        // Явно отключаем триггерный режим, чтобы получать актуальные кадры в потоке.
        arv_device_set_string_feature_value(device, "TriggerMode", "Off", NULL);
        arv_device_set_string_feature_value(device, "AcquisitionMode", "Continuous", NULL);
    }

    // Принудительно ставим Mono8 (8 бит на пиксель, ч/б)
    arv_camera_set_pixel_format(camera, ARV_PIXEL_FORMAT_MONO_8, &error);
    if (error) {
        fprintf(stderr, "Pixel format error: %s\n", error->message);
        g_clear_error(&error);
    }

    printf("Camera connected: %s\n", arv_camera_get_model_name(camera, NULL));

    // 3. Настройка потока данных
    ArvStream *stream = arv_camera_create_stream(camera, NULL, NULL, &error);
    if (!stream) {
        fprintf(stderr, "Stream error: %s\n", error->message);
        return 1;
    }

    // Оптимизация буфера сокета
    g_object_set(stream, "socket-buffer-size", 64 * 1024 * 1024, NULL);

    int payload = arv_camera_get_payload(camera, &error);
    for (int i = 0; i < 15; i++) {
        arv_stream_push_buffer(stream, arv_buffer_new(payload, NULL));
    }

    // 4. Запуск захвата
    arv_camera_start_acquisition(camera, &error);
    if (error) {
        fprintf(stderr, "Acquisition error: %s\n", error->message);
        return 1;
    }

    CameraContext ctx = {camera, stream};

    // 5. Запуск HTTP сервера (на порту 8080)
    struct MHD_Daemon *daemon = MHD_start_daemon(MHD_USE_INTERNAL_POLLING_THREAD, 8080,
                                               NULL, NULL, &handle_request, &ctx, MHD_OPTION_END);

    if (!daemon) {
        fprintf(stderr, "Could not start HTTP daemon\n");
        return 1;
    }

    printf("HTTP server running on http://localhost:8080\n");
    printf("Press Ctrl+C to stop.\n");

    while (1) {
        sleep(1);
    }

    // Очистка (в бесконечном цикле сюда не дойдет, но для порядка)
    MHD_stop_daemon(daemon);
    arv_camera_stop_acquisition(camera, NULL);
    g_object_unref(stream);
    g_object_unref(camera);

    return 0;
}
