import os

SECRET_KEY = os.environ.get("SUPERSET_SECRET_KEY", "iot-dashboard-secret-2024")
SQLALCHEMY_DATABASE_URI = "sqlite:////app/superset/superset.db"