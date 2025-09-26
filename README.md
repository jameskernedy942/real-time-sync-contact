# Real-Time Contact Sync Android App

## Overview
A robust Android application that synchronizes contacts in real-time via AMQP (RabbitMQ). The app runs 24/7 in the background, maintaining a persistent connection to receive contact data and save it to the device's contact database.

## Features
- **24/7 Background Operation**: Runs as a foreground service with persistent notification
- **AMQP Integration**: Connects to RabbitMQ for real-time message consumption
- **Auto-Reconnect**: Automatically reconnects on connection loss
- **Boot Persistence**: Auto-starts on device boot
- **Contact Management**: Creates, updates, and deletes contacts based on incoming messages
- **Callback Support**: Sends success/failure callbacks after processing
- **Battery Optimization**: Requests exemption for uninterrupted operation
- **Android 12+ Compatible**: Full support for latest Android versions

## Architecture

### Components
1. **ContactSyncService**: Foreground service maintaining AMQP connection
2. **AmqpManager**: Handles RabbitMQ connection and message processing
3. **ContactProvider**: Manages Android contact database operations
4. **BootReceiver**: Auto-starts service on device boot
5. **MainActivity**: UI for service management and configuration

## Message Format

### Incoming Contact Data (JSON)
```json
{
  "id": "unique_contact_id",
  "display_name": "John Doe",
  "phone_numbers": [
    {
      "number": "+1234567890",
      "type": "mobile",
      "label": "Personal"
    }
  ],
  "emails": [
    {
      "address": "john@example.com",
      "type": "home"
    }
  ],
  "organization": "Company Name",
  "job_title": "Developer",
  "notes": "Additional notes",
  "addresses": [
    {
      "street": "123 Main St",
      "city": "New York",
      "state": "NY",
      "postal_code": "10001",
      "country": "USA",
      "type": "home"
    }
  ],
  "operation": "create_or_update",
  "timestamp": 1234567890000
}
```

### Callback Message (JSON)
```json
{
  "contact_id": "unique_contact_id",
  "status": "success",
  "message": "Contact created successfully",
  "device_id": "device_uuid",
  "android_contact_id": 123,
  "timestamp": 1234567890000
}
```

## AMQP Configuration

### Default Settings
- **Host**: 10.0.2.2 (Android emulator localhost)
- **Port**: 5672
- **Username**: guest
- **Password**: guest
- **Exchange**: contact_sync_exchange (topic)
- **Queue**: contact_sync_queue
- **Routing Key**: contact.sync
- **Callback Queue**: contact_callback_queue
- **Callback Routing Key**: contact.callback

## Setup Instructions

### Prerequisites
1. Android Studio
2. Android device/emulator (API 24+)
3. RabbitMQ server

### RabbitMQ Setup
```bash
# Install RabbitMQ
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3-management

# Access management UI at http://localhost:15672
# Default credentials: guest/guest
```

### App Installation
1. Clone the repository
2. Open in Android Studio
3. Build and install on device/emulator
4. Grant all required permissions
5. Configure AMQP settings if needed
6. Start the service

## Permissions Required
- `READ_CONTACTS` & `WRITE_CONTACTS`: Contact database access
- `INTERNET` & `ACCESS_NETWORK_STATE`: Network connectivity
- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC`: Background operation
- `RECEIVE_BOOT_COMPLETED`: Auto-start on boot
- `WAKE_LOCK`: Keep device awake during sync
- `POST_NOTIFICATIONS`: Show notifications (Android 13+)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Bypass battery restrictions

## Testing

### Send Test Message to RabbitMQ
```python
import pika
import json

# Connect to RabbitMQ
connection = pika.BlockingConnection(
    pika.ConnectionParameters('localhost')
)
channel = connection.channel()

# Declare exchange
channel.exchange_declare(
    exchange='contact_sync_exchange',
    exchange_type='topic',
    durable=True
)

# Prepare contact data
contact_data = {
    "id": "test_001",
    "display_name": "Test Contact",
    "phone_numbers": [
        {
            "number": "+1234567890",
            "type": "mobile"
        }
    ],
    "emails": [
        {
            "address": "test@example.com",
            "type": "home"
        }
    ],
    "operation": "create_or_update"
}

# Publish message
channel.basic_publish(
    exchange='contact_sync_exchange',
    routing_key='contact.sync',
    body=json.dumps(contact_data),
    properties=pika.BasicProperties(
        delivery_mode=2  # Persistent
    )
)

print("Contact sent!")
connection.close()
```

## Performance & Battery

### Optimization Tips
- Service uses partial wake lock for CPU access
- Reconnection uses exponential backoff
- Heartbeat interval: 60 seconds
- Connection timeout: 30 seconds
- Message processing: Asynchronous with coroutines

### Battery Impact
- Maintains persistent network connection
- Uses foreground service priority
- Recommended: Keep charger connected for production use
- Battery optimization exemption required

## Troubleshooting

### Service Not Starting
1. Check all permissions granted
2. Verify battery optimization disabled
3. Check notification permission (Android 13+)

### Connection Issues
1. Verify RabbitMQ server is running
2. Check network connectivity
3. Verify AMQP credentials
4. For emulator: Use 10.0.2.2 as host
5. For device: Use actual server IP

### Contacts Not Syncing
1. Check contact permissions
2. Verify message format
3. Check Android logs: `adb logcat | grep ContactSync`
4. Verify callback messages for errors

## Security Considerations
- Store AMQP credentials securely
- Use SSL/TLS for production
- Implement authentication tokens
- Sanitize contact data
- Regular security audits

## License
MIT License