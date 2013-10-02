var print = function print(_message)
{
	Packages.java.lang.System.out.println(_message);
};

var util = require('airlift/util');

function deleteFromServer(_uri, _assert)
{
	try
	{  
		var url = new Packages.java.net.URL(_uri);
		var connection = Packages.java.net.HttpURLConnection(url.openConnection());
		connection.setDoInput(true);
		connection.setRequestMethod("DELETE");
		connection.connect();

		var line, result;

		var responseCode = connection.getResponseCode();
		var reader = new Packages.java.io.BufferedReader(new Packages.java.io.InputStreamReader(connection.getInputStream()));

		do
		{
			line = reader.readLine();
			result += line||"";
		}
		while (line)

	} catch (e){
		print("There was an error with testDELETE");
		print(e.message);
		print(e.stack);
	}

	return {responseCode: connection.getResponseCode(), result: result};
};

function getFromServer(_uri, _verbose, _assert)
{
	var line, result = "";
	
	try
	{
		var url = new Packages.java.net.URL(_uri);
		var connection = Packages.java.net.HttpURLConnection(url.openConnection());
		connection.setDoInput(true);
		connection.setRequestMethod('GET');

		var reader = new Packages.java.io.BufferedReader(new Packages.java.io.InputStreamReader(connection.getInputStream()));

		do
		{
			line = reader.readLine();
			result += line||"";
		}
		while (line)
	}	 
	catch (e)
	{
		print("Encountered exception in GET");
		print(e.message);
		print(e.stack);
	}
	finally
	{
		reader && reader.close();
	}
    
    return {responseCode: connection.getResponseCode(), result: result};
};

function placeInServer(_method, _uri, _data, _assert)
{
	var reader, writer;
	try
	{
		var url = new Packages.java.net.URL(_uri);
		var connection = Packages.java.net.HttpURLConnection(url.openConnection());
		connection.setDoOutput(true);
		connection.setDoInput(true);
		connection.setRequestMethod(_method);
		connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		connection.connect();
	
		var OutputStreamWriter = Packages.java.io.OutputStreamWriter;
		writer = new OutputStreamWriter(connection.getOutputStream());
		writer.write(_data);
		writer.flush();
		print("The HTTP response code is equal to: " + connection.getResponseCode());

		var line, result="";
		
		var responseCode = connection.getResponseCode();
		reader = new Packages.java.io.BufferedReader(new Packages.java.io.InputStreamReader(connection.getInputStream()));

		do
		{
			line = reader.readLine();
			result += line||"";
		}
		while (line)

		util.info('HALLOWEEN', 'got this result', result);
			
		return { responseCode: responseCode, result: result }; 
    }
	catch (e)
	{
		print("There was an error with test" + _method);
		print(e.message);
		print(e.stack);
    }
    finally
	{
		reader && reader.close();
		writer && writer.close();
    }
};

exports.serverDelete = function serverDelete(_urlString, _assert)
{
    return deleteFromServer(_urlString, _assert);
};

exports.serverGet = function serverGet(_urlString, _verbose, _assert)
{
    return getFromServer(_urlString, _verbose, _assert);
};

exports.serverCollect = function serverCollect(_urlString, _verbose, _assert)
{
    return getFromServer(_urlString, _verbose, _assert);
};

exports.serverPut = function serverPut(_urlString, _data, _assert)
{
    return placeInServer("PUT",_urlString,_data, _assert); 
};

exports.serverPost = function serverPost(_urlString, _data, _assert)
{
    return placeInServer("POST", _urlString, _data, _assert); 
};