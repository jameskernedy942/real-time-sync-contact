#!/usr/bin/env python3
"""
Test script for Real-Time Contact Sync Android App
Sends test contact data to RabbitMQ and listens for callbacks
"""

import pika
import json
import sys
import uuid
import time
from datetime import datetime

# Configuration
RABBITMQ_HOST = 'localhost'
RABBITMQ_PORT = 5672
RABBITMQ_USER = 'guest'
RABBITMQ_PASS = 'guest'
EXCHANGE_NAME = 'contact_sync_exchange'
QUEUE_NAME = 'contact_sync_queue'
CALLBACK_QUEUE = 'contact_callback_queue'
ROUTING_KEY = 'contact.sync'
CALLBACK_ROUTING_KEY = 'contact.callback'

def create_connection():
    """Create RabbitMQ connection"""
    credentials = pika.PlainCredentials(RABBITMQ_USER, RABBITMQ_PASS)
    parameters = pika.ConnectionParameters(
        host=RABBITMQ_HOST,
        port=RABBITMQ_PORT,
        credentials=credentials
    )
    return pika.BlockingConnection(parameters)

def setup_exchange_and_queues(channel):
    """Setup exchange and queues"""
    # Declare exchange
    channel.exchange_declare(
        exchange=EXCHANGE_NAME,
        exchange_type='topic',
        durable=True
    )

    # Declare queues
    channel.queue_declare(queue=QUEUE_NAME, durable=True)
    channel.queue_declare(queue=CALLBACK_QUEUE, durable=True)

    # Bind queues
    channel.queue_bind(
        exchange=EXCHANGE_NAME,
        queue=QUEUE_NAME,
        routing_key=ROUTING_KEY
    )
    channel.queue_bind(
        exchange=EXCHANGE_NAME,
        queue=CALLBACK_QUEUE,
        routing_key=CALLBACK_ROUTING_KEY
    )

def send_test_contact(channel, contact_id=None):
    """Send a test contact to the queue"""
    if not contact_id:
        contact_id = f"test_{uuid.uuid4().hex[:8]}"

    contact_data = {
        "id": contact_id,
        "display_name": f"Test Contact {contact_id}",
        "phone_numbers": [
            {
                "number": f"+1555{uuid.uuid4().hex[:7]}",
                "type": "mobile",
                "label": "Personal"
            },
            {
                "number": f"+1555{uuid.uuid4().hex[:7]}",
                "type": "work",
                "label": "Office"
            }
        ],
        "emails": [
            {
                "address": f"{contact_id}@example.com",
                "type": "home"
            },
            {
                "address": f"{contact_id}@work.com",
                "type": "work"
            }
        ],
        "organization": "Test Company",
        "job_title": "Software Developer",
        "notes": f"This is a test contact created at {datetime.now()}",
        "addresses": [
            {
                "street": "123 Test Street",
                "city": "Test City",
                "state": "TC",
                "postal_code": "12345",
                "country": "USA",
                "type": "home"
            }
        ],
        "operation": "create_or_update",
        "timestamp": int(time.time() * 1000)
    }

    # Send message
    channel.basic_publish(
        exchange=EXCHANGE_NAME,
        routing_key=ROUTING_KEY,
        body=json.dumps(contact_data, indent=2),
        properties=pika.BasicProperties(
            delivery_mode=2,  # Persistent
            content_type='application/json'
        )
    )

    print(f"✅ Sent contact: {contact_id}")
    print(f"   Display Name: {contact_data['display_name']}")
    print(f"   Phone Numbers: {len(contact_data['phone_numbers'])}")
    print(f"   Emails: {len(contact_data['emails'])}")
    return contact_id

def send_delete_contact(channel, contact_id):
    """Send a delete operation for a contact"""
    contact_data = {
        "id": contact_id,
        "display_name": "",
        "operation": "delete",
        "timestamp": int(time.time() * 1000)
    }

    channel.basic_publish(
        exchange=EXCHANGE_NAME,
        routing_key=ROUTING_KEY,
        body=json.dumps(contact_data),
        properties=pika.BasicProperties(
            delivery_mode=2,
            content_type='application/json'
        )
    )

    print(f"🗑️  Sent delete request for contact: {contact_id}")

def listen_for_callbacks(channel, timeout=30):
    """Listen for callback messages"""
    print(f"\n📡 Listening for callbacks for {timeout} seconds...")

    def callback(ch, method, properties, body):
        try:
            message = json.loads(body)
            status_icon = "✅" if message['status'] == 'success' else "❌"
            print(f"\n{status_icon} Callback received:")
            print(f"   Contact ID: {message.get('contact_id')}")
            print(f"   Status: {message.get('status')}")
            print(f"   Message: {message.get('message')}")
            if message.get('error'):
                print(f"   Error: {message.get('error')}")
            if message.get('android_contact_id'):
                print(f"   Android Contact ID: {message.get('android_contact_id')}")
            print(f"   Device ID: {message.get('device_id')}")
            print(f"   Timestamp: {datetime.fromtimestamp(message.get('timestamp', 0) / 1000)}")
        except json.JSONDecodeError:
            print(f"⚠️  Invalid JSON in callback: {body}")

        ch.basic_ack(delivery_tag=method.delivery_tag)

    channel.basic_consume(
        queue=CALLBACK_QUEUE,
        on_message_callback=callback,
        auto_ack=False
    )

    # Start consuming with timeout
    connection = channel.connection
    connection.process_data_events(time_limit=timeout)

def main():
    """Main function"""
    print("🚀 Real-Time Contact Sync Test Script")
    print("=" * 50)

    try:
        # Create connection and channel
        connection = create_connection()
        channel = connection.channel()

        # Setup exchange and queues
        setup_exchange_and_queues(channel)

        while True:
            print("\n📋 Options:")
            print("1. Send test contact")
            print("2. Send multiple test contacts")
            print("3. Delete a contact")
            print("4. Listen for callbacks")
            print("5. Exit")

            choice = input("\nSelect option (1-5): ").strip()

            if choice == '1':
                contact_id = send_test_contact(channel)
                listen_for_callbacks(channel, timeout=10)

            elif choice == '2':
                count = int(input("How many contacts to send? "))
                contact_ids = []
                for i in range(count):
                    contact_ids.append(send_test_contact(channel))
                    time.sleep(0.1)
                listen_for_callbacks(channel, timeout=15)

            elif choice == '3':
                contact_id = input("Enter contact ID to delete: ").strip()
                send_delete_contact(channel, contact_id)
                listen_for_callbacks(channel, timeout=10)

            elif choice == '4':
                timeout = int(input("Listen timeout in seconds (default 30): ") or 30)
                listen_for_callbacks(channel, timeout=timeout)

            elif choice == '5':
                print("\n👋 Goodbye!")
                break

            else:
                print("❌ Invalid option")

        connection.close()

    except pika.exceptions.AMQPConnectionError:
        print("❌ Could not connect to RabbitMQ")
        print(f"   Host: {RABBITMQ_HOST}:{RABBITMQ_PORT}")
        print("   Make sure RabbitMQ is running")
        sys.exit(1)

    except KeyboardInterrupt:
        print("\n\n👋 Interrupted by user")
        if 'connection' in locals() and connection.is_open:
            connection.close()
        sys.exit(0)

    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()